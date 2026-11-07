package com.imperialreader.data.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareChallengeDetectionTest {
    @Test
    fun requiresChallengeAndRecognizedCloudflareServerHeaders() {
        assertTrue(isChallenge("challenge", "cloudflare"))
        assertTrue(isChallenge("challenge", "cloudflare-nginx"))
        assertFalse(isChallenge(null, "cloudflare"))
        assertFalse(isChallenge("challenge", null))
        assertFalse(isChallenge("challenge", "nginx"))
    }

    @Test
    fun ordinaryHttpStatusesAreNotClassifiedAsChallenges() {
        assertFalse(isChallenge(null, "cloudflare", 403))
        assertFalse(isChallenge(null, "cloudflare", 404))
        assertFalse(isChallenge(null, "cloudflare", 429))
        assertFalse(isChallenge(null, "cloudflare", 500))
    }

    private fun isChallenge(challenge: String?, server: String?, code: Int = 403): Boolean {
        val builder = Response.Builder()
            .request(Request.Builder().url("https://comick.live/api/search").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
        challenge?.let { builder.header("cf-mitigated", it) }
        server?.let { builder.header("Server", it) }
        return CloudflareInterceptor.isCloudflareChallenge(builder.build())
    }
}
