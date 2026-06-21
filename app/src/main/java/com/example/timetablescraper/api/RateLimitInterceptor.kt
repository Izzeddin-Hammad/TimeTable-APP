package com.example.timetablescraper.api

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Token Bucket rate-limiting OkHttp [Interceptor].
 *
 * Limits outbound requests to a configurable rate (default: 5 requests per 10 seconds).
 * When the bucket is empty, the interceptor short-circuits the request and returns
 * an HTTP 429 (Too Many Requests) synthetic response.  The app's fail-safe layer
 * catches this and falls back to cached data with the "⚠️ Offline / Cached Mode" banner.
 *
 * The bucket is global — all requests through the client share the same rate limit.
 *
 * @param maxRequests  Maximum requests allowed per [perSeconds] window (default 5).
 * @param perSeconds   Sliding window duration in seconds (default 10).
 */
class RateLimitInterceptor(
    private val maxRequests: Int = 5,
    private val perSeconds: Int = 10
) : Interceptor {

    // ── State ──────────────────────────────────────────────────────────

    /** Nanosecond timestamp of the current window start. */
    private val windowStartNanos = AtomicLong(System.nanoTime())

    /** Tokens remaining in the current window.  Thread-safe via [AtomicLong]. */
    private val tokens = AtomicLong(maxRequests.toLong())

    // ── Intercept ──────────────────────────────────────────────────────

    override fun intercept(chain: Interceptor.Chain): Response {
        val now = System.nanoTime()
        val windowStart = windowStartNanos.get()
        val elapsed = now - windowStart

        // Window expired → reset
        if (elapsed >= TimeUnit.SECONDS.toNanos(perSeconds.toLong())) {
            // CAS to ensure only one thread resets the window
            if (windowStartNanos.compareAndSet(windowStart, now)) {
                tokens.set(maxRequests.toLong())
            }
        }

        // Try to consume a token
        val remaining = tokens.decrementAndGet()
        if (remaining < 0) {
            // Bucket exhausted — return synthetic 429
            return Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests (client-side rate limit)")
                .body("""
                    {"error": "Rate limit exceeded", "message": "Client-side rate limit: $maxRequests requests per $perSeconds seconds"}
                """.trimIndent().toResponseBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Retry-After", "$perSeconds")
                .build()
        }

        // Token consumed — proceed with the actual request
        return chain.proceed(chain.request())
    }
}
