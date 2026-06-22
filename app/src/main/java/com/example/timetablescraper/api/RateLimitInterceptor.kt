package com.example.timetablescraper.api

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Token Bucket rate-limiting OkHttp [Interceptor] (Per-Course Edition).
 *
 * Limits outbound requests to a configurable rate per specific course identity.
 * (default: 5 requests per 10 seconds per course).
 * * It reads a temporary "X-Course-Identity" header to identify the bucket,
 * strips the header so it doesn't leak to the Scientia API, and tracks rate limits.
 * If no header is present (e.g., for Search requests), the rate limit is bypassed.
 *
 * @param maxRequests  Maximum requests allowed per [perSeconds] window (default 5).
 * @param perSeconds   Sliding window duration in seconds (default 10).
 */
class RateLimitInterceptor(
    private val maxRequests: Int = 5,
    private val perSeconds: Int = 10
) : Interceptor {

    // ── State ──────────────────────────────────────────────────────────

    /** * Thread-safe map storing the token bucket state for each course identity.
     * Key: Course Identity String
     * Value: BucketState
     */
    private val courseBuckets = ConcurrentHashMap<String, BucketState>()

    private data class BucketState(
        var windowStartNanos: AtomicLong,
        var tokens: AtomicLong
    )

    // ── Intercept ──────────────────────────────────────────────────────

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // 1. Extract the course identity from the temporary header
        val courseIdentity = originalRequest.header("X-Course-Identity")

        // 2. Strip the temporary header so the Scientia API doesn't see it
        val cleanRequest = originalRequest.newBuilder()
            .removeHeader("X-Course-Identity")
            .build()

        // 3. If no identity is provided (e.g., Search API call), bypass the limiter
        if (courseIdentity == null) {
            return chain.proceed(cleanRequest)
        }

        // 4. Apply the Token Bucket logic PER COURSE
        val now = System.nanoTime()
        val windowDurationNanos = TimeUnit.SECONDS.toNanos(perSeconds.toLong())

        // Get existing bucket for this course, or create a new full one
        val bucket = courseBuckets.getOrPut(courseIdentity) {
            BucketState(
                windowStartNanos = AtomicLong(now),
                tokens = AtomicLong(maxRequests.toLong())
            )
        }

        val windowStart = bucket.windowStartNanos.get()
        val elapsed = now - windowStart

        // Window expired → reset tokens for this specific course
        if (elapsed >= windowDurationNanos) {
            // CAS to ensure only one thread resets this course's window
            if (bucket.windowStartNanos.compareAndSet(windowStart, now)) {
                bucket.tokens.set(maxRequests.toLong())
            }
        }

        // Try to consume a token
        val remaining = bucket.tokens.decrementAndGet()

        if (remaining < 0) {
            // Bucket exhausted for THIS course — return synthetic 429
            return Response.Builder()
                .request(cleanRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests for Course $courseIdentity")
                .body("""
                    {"error": "Rate limit exceeded", "message": "Client-side rate limit for $courseIdentity: $maxRequests requests per $perSeconds seconds"}
                """.trimIndent().toResponseBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Retry-After", "$perSeconds")
                .build()
        }

        // Token consumed — proceed with the actual cleaned request
        return chain.proceed(cleanRequest)
    }
}