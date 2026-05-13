package com.filepass.network

import android.content.Context
import com.filepass.config.AppConfig
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** 设备信息，用于设备列表展示 */
data class DiscoveredDevice(
    val host: String,
    val port: Int,
    val hostname: String,  // PC 端返回的主机名
)

/**
 * 多策略 PC 发现，优先级：
 *   1. 当前配置 IP + IP 库（并发探测，毫秒级响应）
 *   2. mDNS 广播发现（局域网搜索，秒级）
 * 成功后自动更新配置与 IP 库，下次启动直接命中缓存。
 */
suspend fun findWorkingPc(
    context: Context,
    config: AppConfig,
    totalTimeoutMs: Long = 5_000L
): Pair<String, Int>? {
    val candidates = buildCandidates(config)
    val deadline = System.currentTimeMillis() + totalTimeoutMs

    // Phase 1: 并发探测所有已知 IP（最多 3s）
    if (candidates.isNotEmpty()) {
        val found = raceConnect(candidates, minOf(3_000L, totalTimeoutMs))
        if (found != null) {
            persistResult(config, found)
            return found
        }
    }

    // Phase 2: mDNS 广播发现（利用剩余时间）
    val remaining = deadline - System.currentTimeMillis()
    if (remaining > 500L) {
        val found = mdnsDiscover(context, remaining)
        if (found != null) {
            persistResult(config, found)
            return found
        }
    }

    return null
}

private fun buildCandidates(config: AppConfig): List<Pair<String, Int>> {
    val seen = mutableSetOf<String>()
    val list = mutableListOf<Pair<String, Int>>()
    if (config.isConfigured) {
        seen.add("${config.pcHost}:${config.pcPort}")
        list.add(config.pcHost to config.pcPort)
    }
    for (pair in config.ipLibraryPairs) {
        if (seen.add("${pair.first}:${pair.second}")) list.add(pair)
    }
    return list
}

private fun persistResult(config: AppConfig, result: Pair<String, Int>) {
    config.pcHost = result.first
    config.pcPort = result.second
    config.isConnected = true
    config.addToIpLibrary(result.first, result.second)
}

/** 并发探测所有候选 IP，返回第一个 ping 成功的 */
private suspend fun raceConnect(
    candidates: List<Pair<String, Int>>,
    timeoutMs: Long
): Pair<String, Int>? {
    val result = CompletableDeferred<Pair<String, Int>?>()
    val remaining = AtomicInteger(candidates.size)

    val jobs = candidates.map { (host, port) ->
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val ok = ApiClient("http://$host:$port").ping().isSuccess
                if (ok) result.complete(host to port)
            } catch (_: Exception) { }
            if (remaining.decrementAndGet() == 0) result.complete(null)
        }
    }

    val found = withTimeoutOrNull(timeoutMs) { result.await() }
    jobs.forEach { it.cancel() }
    return found
}

/** mDNS 广播发现，返回第一个响应的服务地址 */
private suspend fun mdnsDiscover(context: Context, timeoutMs: Long): Pair<String, Int>? {
    val discovery = PcDiscovery(context)
    return try {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Pair<String, Int>?> { cont ->
                try {
                    discovery.startDiscovery(onFound = { host, port ->
                        try { cont.resumeWith(Result.success(host to port)) } catch (_: Exception) { }
                    })
                } catch (e: Exception) {
                    try { cont.resumeWith(Result.success(null)) } catch (_: Exception) { }
                }
                cont.invokeOnCancellation {
                    try { discovery.stopDiscovery() } catch (_: Exception) { }
                }
            }
        }
    } finally {
        try { discovery.stopDiscovery() } catch (_: Exception) { }
    }
}

/**
 * 扫描所有可用设备：IP 库 + mDNS，返回在线设备列表。
 */
suspend fun findAllDevices(
    context: Context,
    config: AppConfig,
    totalTimeoutMs: Long = 6_000L
): List<DiscoveredDevice> {
    val devices = mutableListOf<DiscoveredDevice>()
    val seen = mutableSetOf<String>()

    // Phase 1: 并发 ping IP 库中的所有已知 IP
    val candidates = buildCandidates(config)
    if (candidates.isNotEmpty()) {
        val results = probeAll(candidates, minOf(3_000L, totalTimeoutMs))
        for (dev in results) {
            val key = "${dev.host}:${dev.port}"
            if (seen.add(key)) devices.add(dev)
        }
    }

    // Phase 2: mDNS 发现（收集剩余时间内出现的所有设备）
    val deadline = System.currentTimeMillis() + totalTimeoutMs
    val remaining = deadline - System.currentTimeMillis()
    if (remaining > 500L) {
        val mdnsDevices = mdnsDiscoverAll(context, remaining)
        for (dev in mdnsDevices) {
            val key = "${dev.host}:${dev.port}"
            if (seen.add(key)) devices.add(dev)
        }
    }

    return devices
}

/** 并发 ping 所有候选 IP，返回所有在线设备 */
private suspend fun probeAll(
    candidates: List<Pair<String, Int>>,
    timeoutMs: Long
): List<DiscoveredDevice> {
    val results = mutableListOf<DiscoveredDevice>()
    val jobs = candidates.map { (host, port) ->
        CoroutineScope(Dispatchers.IO + SupervisorJob()).async {
            try {
                val api = ApiClient("http://$host:$port")
                var hostname = host
                api.ping(onInfo = {}).onSuccess { name -> hostname = name }
                    .getOrNull() ?: return@async null
                DiscoveredDevice(host, port, hostname)
            } catch (_: Exception) { null }
        }
    }
    withTimeoutOrNull(timeoutMs) {
        jobs.forEach { deferred ->
            val dev = try { deferred.await() } catch (_: Exception) { null }
            if (dev != null) synchronized(results) { results.add(dev) }
        }
    }
    jobs.forEach { it.cancel() }
    return results
}

/** mDNS 广播发现所有设备（收集指定时间内出现的） */
private suspend fun mdnsDiscoverAll(context: Context, timeoutMs: Long): List<DiscoveredDevice> {
    val devices = mutableListOf<DiscoveredDevice>()
    val discovery = PcDiscovery(context)
    try {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                try {
                    discovery.startDiscovery(onFound = { host, port ->
                        val key = "$host:$port"
                        val alreadyHave = synchronized(devices) { devices.any { "${it.host}:${it.port}" == key } }
                        if (!alreadyHave) {
                            // Ping to get hostname
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    var hostname = host
                                    ApiClient("http://$host:$port").ping()
                                        .onSuccess { name -> hostname = name }
                                    synchronized(devices) {
                                        if (devices.none { "${it.host}:${it.port}" == key }) {
                                            devices.add(DiscoveredDevice(host, port, hostname))
                                        }
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                    })
                } catch (e: Exception) {
                    try { cont.resumeWith(Result.success(Unit)) } catch (_: Exception) { }
                }
                cont.invokeOnCancellation {
                    try { discovery.stopDiscovery() } catch (_: Exception) { }
                }
            }
        }
    } finally {
        try { discovery.stopDiscovery() } catch (_: Exception) { }
    }
    return devices
}
