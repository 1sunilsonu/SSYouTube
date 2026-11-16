package com.tensei.ytxtube

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


object Net {

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private data class DnsEntry(val addresses: List<InetAddress>, val expiresAt: Long)
    private val dnsCache = ConcurrentHashMap<String, DnsEntry>()

    private val fastDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()
            val cached = dnsCache[hostname]
            if (cached != null && cached.expiresAt > now) {
                return cached.addresses
            }
            val raw = Dns.SYSTEM.lookup(hostname)
            val sorted = raw.sortedBy { if (it is Inet4Address) 0 else 1 }
            if (sorted.isNotEmpty()) {
                dnsCache[hostname] = DnsEntry(sorted, now + 5 * 60 * 1000L)
            }
            return sorted
        }
    }

    val client: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 48
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(fastDns)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(64, 5, TimeUnit.MINUTES))
            .build()
    }


    @Volatile private var mediaHost: String? = null

    fun noteMediaHost(url: String?) {
        url?.toHttpUrlOrNull()?.host?.let { mediaHost = it }
    }

    private fun warmHost(host: String) {
        val request = okhttp3.Request.Builder()
            .url("https://$host/generate_204")
            .head()
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
        })
    }

    fun prewarmMediaConnection() {
        mediaHost?.let { warmHost(it) }
        warmHost("i.ytimg.com")
        warmHost("youtubei.googleapis.com")
    }
}


class NewPipeDownloader private constructor(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val langCode = runCatching { LanguageSettings.effectiveLanguageCode() }.getOrNull() ?: "en"
        val countryCode = runCatching { LanguageSettings.effectiveCountryCode() }.getOrNull() ?: "US"
        val isYouTube = request.url().contains("youtube.com") || request.url().contains("youtubei")

        var sendData = request.dataToSend()
        if (sendData != null && isYouTube && langCode.isNotEmpty()) {
            val raw = String(sendData, Charsets.UTF_8)
            if (raw.contains("\"context\"") || raw.contains("\"client\"")) {
                var modified = raw
                if (modified.contains("\"hl\":")) {
                    modified = modified.replace(Regex("\"hl\"\\s*:\\s*\"[^\"]*\""), "\"hl\":\"$langCode\"")
                } else if (modified.contains("\"client\":{")) {
                    modified = modified.replace("\"client\":{", "\"client\":{\"hl\":\"$langCode\",\"gl\":\"$countryCode\",")
                }
                if (modified.contains("\"gl\":")) {
                    modified = modified.replace(Regex("\"gl\"\\s*:\\s*\"[^\"]*\""), "\"gl\":\"$countryCode\"")
                }
                sendData = modified.toByteArray(Charsets.UTF_8)
            }
        }
        val body = sendData?.toRequestBody()

        var targetUrl = request.url()
        if (isYouTube && langCode.isNotEmpty()) {
            val httpUrl = targetUrl.toHttpUrlOrNull()
            if (httpUrl != null) {
                targetUrl = httpUrl.newBuilder()
                    .removeAllQueryParameters("hl")
                    .addQueryParameter("hl", langCode)
                    .removeAllQueryParameters("gl")
                    .addQueryParameter("gl", countryCode)
                    .build().toString()
            }
        }

        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(targetUrl)
            .addHeader("User-Agent", Net.USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        builder.removeHeader("Accept-Language")
        builder.addHeader("Accept-Language", "$langCode-$countryCode,$langCode;q=0.9,en-US;q=0.8,en;q=0.7")

        val existingCookie = request.headers()["Cookie"]?.firstOrNull() ?: ""
        val prefCookie = "PREF=hl=$langCode&gl=$countryCode"
        val mergedCookie = buildString {
            if (Account.signedIn) {
                val acctCookies = Account.cookieHeader()
                if (acctCookies.isNotEmpty()) append(acctCookies).append("; ")
            }
            if (existingCookie.isNotEmpty()) append(existingCookie).append("; ")
            if (!contains("PREF=")) append(prefCookie)
        }.trim().removeSuffix(";")

        if (mergedCookie.isNotEmpty()) {
            builder.removeHeader("Cookie")
            builder.addHeader("Cookie", mergedCookie)
        }

        if (isYouTube && Account.signedIn) {
            val auth = Account.authorization()
            if (auth != null && !request.headers().containsKey("Authorization")) {
                builder.addHeader("Authorization", auth)
            }
        }
        client.newCall(builder.build()).execute().use { response ->

            if (response.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            val text = response.body?.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                if (PlayerFallback.handles(request.url())) PlayerFallback.substitute(request.url(), request.dataToSend(), text) else text,
                response.request.url.toString(),
            )
        }
    }

    companion object {
        val instance: NewPipeDownloader by lazy { NewPipeDownloader(Net.client) }
    }
}
