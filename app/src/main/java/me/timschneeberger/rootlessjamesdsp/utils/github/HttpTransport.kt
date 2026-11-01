package me.timschneeberger.rootlessjamesdsp.utils.github

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP reply. Closing it drops the connection, which also unblocks a read stuck on another thread. */
class HttpResponse(
    val code: Int,
    /** Lower-cased header names. */
    val headers: Map<String, String>,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { body.close() }
        onClose()
    }
}

/** Opens GET requests. Tests swap in recorded replies; the app uses [UrlTransport]. */
fun interface HttpTransport {
    fun get(url: String, accept: String?): HttpResponse
}

object UrlTransport : HttpTransport {
    override fun get(url: String, accept: String?): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "RootlessViPER4Android")
        accept?.let { conn.setRequestProperty("Accept", it) }
        if (url.startsWith(GithubClient.API)) conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        return try {
            val code = conn.responseCode
            val headers = conn.headerFields
                .filterKeys { it != null }
                .map { (k, v) -> k.lowercase() to (v.firstOrNull() ?: "") }
                .toMap()
            val body = if (code in 200..299) conn.inputStream
                else conn.errorStream ?: ByteArrayInputStream(ByteArray(0))
            HttpResponse(code, headers, body) { conn.disconnect() }
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }
}
