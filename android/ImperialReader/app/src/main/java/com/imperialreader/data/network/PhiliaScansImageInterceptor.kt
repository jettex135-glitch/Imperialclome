package com.imperialreader.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

class PhiliaScansImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val metadata = request.url.fragment?.split(';', limit = 8)
            ?.takeIf { it.size == 8 && it[0] == "philia" }
            ?: return chain.proceed(request)
        val response = chain.proceed(request)
        if (!response.isSuccessful || !ENCRYPTED_PAGE.matches(request.url.pathSegments.lastOrNull().orEmpty())) {
            return response
        }
        val body = response.body ?: throw IOException("PhiliaScans returned an empty page")
        if (body.contentLength() > MAX_IMAGE_BYTES) {
            response.close()
            throw IOException("PhiliaScans page is too large to decode")
        }
        val contentType = metadata[2].toMediaTypeOrNull() ?: body.contentType()

        val decoded = try {
            decode(body.bytes(), metadata)
        } catch (e: Exception) {
            response.close()
            if (e is IOException) throw e
            throw IOException("Could not decode this PhiliaScans page", e)
        }
        return response.newBuilder()
            .body(decoded.toResponseBody(contentType))
            .build()
    }

    private fun decode(encrypted: ByteArray, fields: List<String>): ByteArray {
        if (encrypted.size < 4) throw IOException("PhiliaScans returned an invalid page")
        if (encrypted.size > MAX_IMAGE_BYTES) throw IOException("PhiliaScans page is too large to decode")

        val pageIndex = fields[7].toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("PhiliaScans returned an invalid page index")
        val gridSize = fields[4].toIntOrNull() ?: 0
        val key = deriveChapterKey(fields)
        val scheme = when {
            encrypted.size >= 2 && encrypted[0] == 0xff.toByte() && encrypted[1] == 0x02.toByte() -> 2
            encrypted.size >= 2 && encrypted[0] == 0xff.toByte() && encrypted[1] == 0x03.toByte() -> 3
            encrypted.size >= 2 && encrypted[0] == 0xff.toByte() && encrypted[1] == 0x04.toByte() -> 4
            else -> 0
        }
        val headerOffset = if (scheme == 0) 0 else 2
        if (encrypted.size < headerOffset + 4) throw IOException("PhiliaScans returned a truncated page")
        val dimensions = ByteBuffer.wrap(encrypted, headerOffset, 4).order(ByteOrder.BIG_ENDIAN)
        val width = dimensions.short.toInt() and 0xffff
        val height = dimensions.short.toInt() and 0xffff
        val payloadOffset = headerOffset + 4
        val payload = encrypted.copyOfRange(payloadOffset, encrypted.size)

        val plain = when (scheme) {
            2 -> cryptAesCtr(payload, hmac(key, "aesctr:$pageIndex"))
            3 -> cryptChaCha(payload, hmac(key, "cc:$pageIndex"))
            4 -> cryptAesCtr(payload, hmac(key, "aesctr4:$pageIndex"))
            else -> cryptXor(payload, key, pageIndex)
        }
        if (fields[1] != "1" || scheme == 3 || scheme == 4) return plain
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_IMAGE_PIXELS || gridSize !in 1..64) {
            throw IOException("PhiliaScans returned invalid image dimensions")
        }
        return unswizzle(plain, key, pageIndex, gridSize, width, height, fields[2])
    }

    private fun deriveChapterKey(fields: List<String>): ByteArray {
        val keyA = fields[5].decodeBase64OrNull()
        val keyB = fields[6].decodeBase64OrNull()
        val key = if (keyA != null && keyB != null) {
            if (keyA.size != keyB.size || keyA.isEmpty()) {
                throw IOException("PhiliaScans returned invalid session keys")
            }
            ByteArray(keyA.size) { keyA[it] xor keyB[it] }
        } else {
            fields[3].decodeBase64OrNull()
                ?: throw IOException("PhiliaScans returned an invalid chapter key")
        }
        if (key.size != 32) throw IOException("PhiliaScans returned an invalid chapter key")
        return key
    }

    private fun String.decodeBase64OrNull(): ByteArray? =
        takeIf { it.isNotBlank() && it != "null" }?.let {
            try {
                Base64.getDecoder().decode(it)
            } catch (e: IllegalArgumentException) {
                try {
                    Base64.getUrlDecoder().decode(it)
                } catch (urlError: IllegalArgumentException) {
                    throw IOException("PhiliaScans returned invalid encoded page data", urlError)
                }
            }
        }

    private fun cryptAesCtr(input: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return cipher.doFinal(input)
    }

    private fun cryptXor(input: ByteArray, key: ByteArray, pageIndex: Int): ByteArray {
        val result = input.copyOf()
        var offset = 0
        var blockNumber = 0
        while (offset < result.size) {
            val block = hmac(key, "page:$pageIndex:$blockNumber")
            val count = minOf(block.size, result.size - offset)
            repeat(count) { i -> result[offset + i] = result[offset + i] xor block[i] }
            offset += count
            blockNumber++
        }
        return result
    }

    private fun cryptChaCha(input: ByteArray, key: ByteArray): ByteArray {
        val result = input.copyOf()
        val nonce = ByteArray(12)
        var offset = 0
        var counter = 0
        while (offset < result.size) {
            val streamBlock = chachaBlock(key, nonce, counter++)
            val count = minOf(streamBlock.size, result.size - offset)
            repeat(count) { i -> result[offset + i] = result[offset + i] xor streamBlock[i] }
            offset += count
        }
        return result
    }

    private fun chachaBlock(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        fun intAt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 255) or
                ((bytes[offset + 1].toInt() and 255) shl 8) or
                ((bytes[offset + 2].toInt() and 255) shl 16) or
                ((bytes[offset + 3].toInt() and 255) shl 24)

        val state = intArrayOf(
            0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
            *IntArray(8) { intAt(key, it * 4) },
            counter,
            intAt(nonce, 0), intAt(nonce, 4), intAt(nonce, 8)
        )
        val work = state.copyOf()
        repeat(10) {
            quarterRound(work, 0, 4, 8, 12)
            quarterRound(work, 1, 5, 9, 13)
            quarterRound(work, 2, 6, 10, 14)
            quarterRound(work, 3, 7, 11, 15)
            quarterRound(work, 0, 5, 10, 15)
            quarterRound(work, 1, 6, 11, 12)
            quarterRound(work, 2, 7, 8, 13)
            quarterRound(work, 3, 4, 9, 14)
        }
        return ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).also { output ->
            work.indices.forEach { output.putInt(work[it] + state[it]) }
        }.array()
    }

    private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] += state[b]
        state[d] = Integer.rotateLeft(state[d] xor state[a], 16)
        state[c] += state[d]
        state[b] = Integer.rotateLeft(state[b] xor state[c], 12)
        state[a] += state[b]
        state[d] = Integer.rotateLeft(state[d] xor state[a], 8)
        state[c] += state[d]
        state[b] = Integer.rotateLeft(state[b] xor state[c], 7)
    }

    private fun unswizzle(
        bytes: ByteArray,
        key: ByteArray,
        pageIndex: Int,
        gridSize: Int,
        width: Int,
        height: Int,
        mime: String
    ): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("PhiliaScans returned an unreadable scrambled image")
        val restored = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(restored)
        val count = gridSize * gridSize
        val permutation = IntArray(count) { it }
        val tileSeed = hmac(key, "tiles:$pageIndex")
        var randomBlock = 0
        var randomOffset = 0
        var randomBytes = ByteArray(0)
        fun nextRandom(): Long {
            if (randomOffset + 4 > randomBytes.size) {
                randomBytes = hmac(tileSeed, "perm:${randomBlock++}")
                randomOffset = 0
            }
            val result = (randomBytes[randomOffset].toLong() and 255) or
                ((randomBytes[randomOffset + 1].toLong() and 255) shl 8) or
                ((randomBytes[randomOffset + 2].toLong() and 255) shl 16) or
                ((randomBytes[randomOffset + 3].toLong() and 255) shl 24)
            randomOffset += 4
            return result and 0xffffffffL
        }
        for (i in count - 1 downTo 1) {
            val other = (nextRandom() % (i + 1)).toInt()
            val old = permutation[i]
            permutation[i] = permutation[other]
            permutation[other] = old
        }
        val sourceForDestination = IntArray(count)
        permutation.forEachIndexed { source, destination -> sourceForDestination[destination] = source }
        val sourceRect = Rect()
        val destinationRect = Rect()
        for (destination in 0 until count) {
            val source = sourceForDestination[destination]
            sourceRect.set(
                source % gridSize * bitmap.width / gridSize,
                source / gridSize * bitmap.height / gridSize,
                (source % gridSize + 1) * bitmap.width / gridSize,
                (source / gridSize + 1) * bitmap.height / gridSize
            )
            destinationRect.set(
                destination % gridSize * width / gridSize,
                destination / gridSize * height / gridSize,
                (destination % gridSize + 1) * width / gridSize,
                (destination / gridSize + 1) * height / gridSize
            )
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, null)
        }
        bitmap.recycle()
        val format = when (mime.lowercase()) {
            "image/png" -> Bitmap.CompressFormat.PNG
            "image/webp", "image/webp-lossless" -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val output = java.io.ByteArrayOutputStream()
        val quality = if (format == Bitmap.CompressFormat.JPEG) 90 else 100
        val saved = restored.compress(format, quality, output)
        restored.recycle()
        if (!saved) throw IOException("Could not save the decoded PhiliaScans image")
        return output.toByteArray()
    }

    private fun hmac(key: ByteArray, message: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message.toByteArray(Charsets.UTF_8))
        }

    private companion object {
        const val MAX_IMAGE_BYTES = 128 * 1024 * 1024
        const val MAX_IMAGE_PIXELS = 100_000_000L
        val ENCRYPTED_PAGE = Regex(""".*_s\.[^.]+$""")
    }
}
