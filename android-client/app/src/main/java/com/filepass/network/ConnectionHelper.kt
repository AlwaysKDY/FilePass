package com.filepass.network

import android.content.Context
import com.filepass.config.AppConfig
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多策略 PC 发现，优先级：
 *   1. 当前配置 IP + IP 库（并发探测，毫秒级响应）
 *   2. mDNS 广播发现（局域网搜索，秒级）
 * 成功后自动更新配置与 IP 库，下次启动直接命中缓存。
 */
suspend fun findWorkingPc(
    context: Context,
    config: AppConfig,
    totalTimeoutMs: Long = 10_000L
): Pair<String, Int>? {
    val candidates = buildCandidates(config)
    val deadline = System.currentTimeMillis() + totalTimeoutMs

    // Phase 1: 并发探测所有已知 IP（最多 4s）
    if (candidates.isNotEmpty()) {
        val found = raceConnect(candidates, minOf(4_000L, totalTimeoutMs))
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
