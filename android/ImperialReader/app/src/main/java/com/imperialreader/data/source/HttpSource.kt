package com.imperialreader.data.source

import com.imperialreader.App
import com.imperialreader.data.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.net.URLEncoder

class HttpStatusException(
    val code: Int,
    url: String,
    val retryAfterMs: Long? = null
) : IOException(
    if (retryAfterMs != null) {
        "HTTP $code for $url; server requested a retry after ${retryAfterMs / 1_000L} seconds"
    } else {
        "HTTP $code for $url"
    }
)

abstract class HttpSource : Source {

    protected val client get() = App.instance.network.client

    open val extraHeaders: Map<String, String> get() = mapOf("Referer" to "$baseUrl/")

    override fun imageHeaders(): Map<String, String> = extraHeaders

    override suspend fun searchByAuthor(query: String, page: Int): MangasPage =
        super<Source>.searchByAuthor(query, page)

    protected open suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        execute(Request.Builder().url(absoluteUrl(url)), headers)

    protected suspend fun postString(
        url: String,
        form: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): String {
        val body = if (form.isEmpty()) {
            ByteArray(0).toRequestBody(null)
        } else {
            FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        }
        return execute(Request.Builder().url(absoluteUrl(url)).post(body), headers)
    }

    private suspend fun execute(builder: Request.Builder, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            headers.forEach { (k, v) -> builder.header(k, v) }
            val request = builder.build()
            client.await(request).use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw HttpStatusException(
                        resp.code,
                        request.url.toString(),
                        parseRetryAfterMillis(resp.header("Retry-After"))
                    )
                }
                text
            }
        }

    protected fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    protected fun absoluteUrl(url: String): String = resolveHttpSourceUrl(baseUrl, url)
}

internal fun resolveHttpSourceUrl(baseUrl: String, url: String): String = when {
    url.startsWith("http://") || url.startsWith("https://") -> url
    url.startsWith("//") -> "https:$url"
    url.startsWith("/") -> baseUrl.trimEnd('/') + url
    else -> baseUrl.trimEnd('/') + "/" + url
}

internal fun parseRetryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    if (value.isNullOrBlank()) return null
    value.trim().toLongOrNull()?.let { seconds ->
        val safeSeconds = seconds.coerceAtLeast(0L)
        return if (safeSeconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else safeSeconds * 1_000L
    }
    return try {
        (DateTimeFormatter.RFC_1123_DATE_TIME.parse(value.trim(), Instant::from).toEpochMilli() - nowMillis)
            .coerceAtLeast(0L)
    } catch (_: Exception) {
        null
    }
}
