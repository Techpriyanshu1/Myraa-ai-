package com.example.core

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP/2 connection-pooled OkHttpClient so Gemini Intelligence,
 * Gemini Multimodal Audio, and Gemini Neural TTS reuse warm TLS sockets
 * instead of paying cold connection handshake latency on every turn.
 *
 * Uses IPv4-first DNS ordering (keeping IPv6 as fallback) to prevent
 * blackholed IPv6 stalls on Indian cellular networks (Jio/Airtel/Wi-Fi).
 */
object SharedHttpClient {
    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet6Address) 1 else 0 }
        }
    }

    val instance: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 12
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(ipv4FirstDns)
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(28, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
