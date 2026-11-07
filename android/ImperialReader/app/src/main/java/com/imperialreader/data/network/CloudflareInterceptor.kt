package com.imperialreader.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantReadWriteLock

internal class CloudflareInterceptor(
    context: Context,
    private val cookieJar: CookieJar,
    private val userAgent: () -> String,
    private val timeoutMs: Long = CHALLENGE_TIMEOUT_MS
) : Interceptor {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostLocks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val hostLock = hostLocks.computeIfAbsent(request.url.host) { ReentrantReadWriteLock(true) }
        val outcome = withLock(hostLock.readLock(), chain) {
            val response = chain.proceed(request)
            if (!isCloudflareChallenge(response)) {
                ChallengeOutcome(response, null, false)
            } else {
                ChallengeOutcome(response, clearance(request), true)
            }
        }
        if (!outcome.challenge) return outcome.response

        val resolution = withLock(hostLock.writeLock(), chain) {
            if (hasNewClearance(request, outcome.clearance)) {
                outcome.response.close()
                ChallengeResolution(solved = true)
            } else {
                resolveChallenge(request, outcome.response, outcome.clearance, chain)
            }
        }
        if (!resolution.solved) {
            outcome.response.close()
            val cause = resolution.cause
            throw CloudflareException(
                request.url.toString(),
                cause?.message?.let {
                    "Cloudflare challenge could not be resolved: $it. Open the source in WebView, then retry."
                } ?: "Cloudflare challenge was not solved before timeout. Open the source in WebView, then retry.",
                cause
            )
        }

        val retried = chain.proceed(request)
        if (isCloudflareChallenge(retried)) {
            retried.close()
            throw CloudflareException(
                request.url.toString(),
                "Cloudflare is still blocking this request. Open the source in WebView, complete its check, then retry."
            )
        }
        return retried
    }

    private fun resolveChallenge(
        request: Request,
        response: Response,
        previousClearance: String?,
        chain: Interceptor.Chain
    ): ChallengeResolution {
        response.close()
        removeClearance(request)

        val latch = CountDownLatch(1)
        var webView: WebView? = null
        val rendererGone = AtomicBoolean(false)
        val solved = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val challengeUrl = if (request.method == "GET") {
            request.url.toString()
        } else {
            request.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        }
        val headers = request.headers.toMultimap()
            .filterKeys { it.lowercase() !in WEBVIEW_MANAGED_HEADERS }
            .mapValues { (_, values) -> values.joinToString(", ") }

        mainHandler.post {
            try {
                webView = createWebView(request).also { view ->
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            CookieManager.getInstance().flush()
                            if (hasNewClearance(request, previousClearance)) {
                                solved.set(true)
                                latch.countDown()
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            webRequest: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            if (webRequest?.isForMainFrame == true &&
                                errorResponse?.responseHeaders?.any { (name, value) ->
                                    name.equals("cf-mitigated", ignoreCase = true) &&
                                        value.equals("challenge", ignoreCase = true)
                                } == true
                            ) {
                                Log.d(TAG, "WebView received a Cloudflare challenge for ${request.url.host}")
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail
                        ): Boolean {
                            rendererGone.set(true)
                            failure.set(IOException(
                                "WebView renderer exited during Cloudflare resolution (crashed=${detail.didCrash()})"
                            ))
                            latch.countDown()
                            return true
                        }
                    }
                    view.loadUrl(challengeUrl, headers)
                }
            } catch (e: Exception) {
                failure.set(e)
                latch.countDown()
            }
        }

        val expiresAt = SystemClock.elapsedRealtime() + timeoutMs
        try {
            while (!solved.get() && !rendererGone.get() && !chain.call().isCanceled()) {
                val remaining = expiresAt - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                latch.await(minOf(remaining, WAIT_INTERVAL_MS), TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failure.set(InterruptedIOException("Cloudflare resolution was interrupted").apply { initCause(e) })
        } finally {
            mainHandler.post {
                webView?.let { view ->
                    if (!rendererGone.get()) {
                        view.stopLoading()
                        view.destroy()
                    }
                }
            }
        }

        if (chain.call().isCanceled()) {
            throw InterruptedIOException("Request cancelled during Cloudflare resolution")
        }
        if (solved.get() && hasNewClearance(request, previousClearance)) {
            return ChallengeResolution(solved = true)
        }

        failure.get()?.let { Log.w(TAG, "Cloudflare resolution failed for ${request.url.host}", it) }
        if (SystemClock.elapsedRealtime() >= expiresAt) {
            Log.w(TAG, "Cloudflare resolution timed out for ${request.url.host}")
        }
        return ChallengeResolution(solved = false, cause = failure.get())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(request: Request): WebView =
        WebView(appContext).apply {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = request.header("User-Agent") ?: userAgent()
            webChromeClient = android.webkit.WebChromeClient()
        }

    private fun clearance(request: Request): String? =
        cookieJar.loadForRequest(request.url).firstOrNull { it.name == CLEARANCE_COOKIE }?.value

    private fun hasNewClearance(request: Request, previous: String?): Boolean =
        clearance(request)?.let { it.isNotBlank() && it != previous } == true

    private fun removeClearance(request: Request) {
        val latch = CountDownLatch(1)
        mainHandler.post {
            CookieManager.getInstance().setCookie(
                request.url.toString(),
                "$CLEARANCE_COOKIE=; Max-Age=0; Path=/"
            ) {
                CookieManager.getInstance().flush()
                latch.countDown()
            }
        }
        try {
            if (!latch.await(COOKIE_UPDATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out clearing stale Cloudflare clearance for ${request.url.host}")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while clearing Cloudflare cookies").apply { initCause(e) }
        }
    }

    private fun <T> withLock(lock: Lock, chain: Interceptor.Chain, block: () -> T): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (chain.call().isCanceled()) throw InterruptedIOException("Request cancelled while waiting for host lock")
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) throw InterruptedIOException("Timed out waiting for Cloudflare host lock")
            try {
                if (lock.tryLock(minOf(remaining, WAIT_INTERVAL_MS), TimeUnit.MILLISECONDS)) break
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while waiting for Cloudflare host lock").apply {
                    initCause(e)
                }
            }
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private data class ChallengeOutcome(
        val response: Response,
        val clearance: String?,
        val challenge: Boolean
    )

    private data class ChallengeResolution(
        val solved: Boolean,
        val cause: Throwable? = null
    )

    companion object {
        private const val TAG = "Cloudflare"
        private const val CLEARANCE_COOKIE = "cf_clearance"
        private const val CHALLENGE_TIMEOUT_MS = 30_000L
        private const val COOKIE_UPDATE_TIMEOUT_MS = 2_000L
        private const val WAIT_INTERVAL_MS = 200L
        private val WEBVIEW_MANAGED_HEADERS = setOf(
            "connection",
            "content-length",
            "cookie",
            "host",
            "user-agent"
        )
        private val CLOUDFLARE_SERVERS = setOf("cloudflare", "cloudflare-nginx")

        internal fun isCloudflareChallenge(response: Response): Boolean =
            response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true &&
                response.header("Server")?.lowercase()?.trim() in CLOUDFLARE_SERVERS
    }
}
