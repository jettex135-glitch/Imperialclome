package com.imperialreader.data.network

import android.content.Context
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when a site answers with a Cloudflare challenge. The UI offers to solve it in a WebView. */
class CloudflareException(
    val url: String,
    message: String = "Cloudflare protection detected. Open the site in the WebView and solve the challenge, then retry.",
    cause: Throwable? = null
) : IOException(message, cause)

class NetworkHelper(@Suppress("UNUSED_PARAMETER") context: Context) {

    val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Shares cookies with the WebView so a solved Cloudflare challenge carries over to OkHttp. */
    internal val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cm = CookieManager.getInstance()
            cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(PhiliaScansImageInterceptor())
        .addInterceptor { chain ->
            val req = chain.request()
            val b = req.newBuilder()
            if (req.header("User-Agent") == null) b.header("User-Agent", userAgent)
            chain.proceed(b.build())
        }
        .addInterceptor(CloudflareInterceptor(context, cookieJar, userAgent = { userAgent }))
        .build()
}
