package com.filepass.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * mDNS 服务发现 — 自动定位局域网内的 PC 端。
 * 使用 Android 系统原生 NsdManager，零额外依赖。
 */
class PcDiscovery(private val context: Context) {

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    /**
     * 开始 mDNS 服务发现。
     * @param onFound 发现 PC 后回调 (host, port)
     * @param onLost 服务消失回调
     */
    fun startDiscovery(
        onFound: (host: String, port: Int) -> Unit,
        onLost: (() -> Unit)? = null
    ) {
        if (isDiscovering) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS 发现已启动: $serviceType")
                isDiscovering = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "发现服务: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType == SERVICE_TYPE ||
                    serviceInfo.serviceName.startsWith("FilePass-")
                ) {
                    resolveService(serviceInfo, onFound)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "服务丢失: ${serviceInfo.serviceName}")
                onLost?.invoke()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS 发现已停止")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "启动发现失败: errorCode=$errorCode")
                isDiscovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "停止发现失败: errorCode=$errorCode")
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun resolveService(
        serviceInfo: NsdServiceInfo,
        onFound: (host: String, port: Int) -> Unit
    ) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "解析服务失败: ${info.serviceName}, errorCode=$errorCode")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                val port = info.port
                if (host != null) {
                    Log.i(TAG, "已定位 PC: $host:$port")
                    onFound(host, port)
                }
            }
        })
    }

    fun stopDiscovery() {
        if (isDiscovering && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.w(TAG, "停止发现异常: ${e.message}")
            }
            isDiscovering = false
        }
    }

    companion object {
        private const val TAG = "PcDiscovery"
        private const val SERVICE_TYPE = "_filepass._tcp."
    }
}
