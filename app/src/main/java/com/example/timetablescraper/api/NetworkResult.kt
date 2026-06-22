package com.example.timetablescraper.api

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import retrofit2.HttpException // If you are using Retrofit, this is the standard HTTP Exception

/**
 * Typed result wrapper that classifies every network outcome into one of
 * four categories. This allows the UI and repository to make structural
 * decisions (show cached data vs. error) without parsing exception strings.
 *
 * ## Classification rules
 *
 * | Scenario                     | [NetworkResult]             |
 * |------------------------------|-----------------------------|
 * | Successful 2xx response      | [Success]                   |
 * | HTTP 429 (Too Many Requests) | [HttpError] with 429 code   |
 * | HTTP 5xx (Server Error)      | [HttpError] with 5xx code   |
 * | Timeout / UnknownHost / SSL  | [TransportError]            |
 * | Any other failure            | [TransportError]            |
 */
sealed class NetworkResult<out T> {

    /** Successful response with parsed [data]. */
    data class Success<T>(val data: T) : NetworkResult<T>()

    /** HTTP-level error (non-2xx). */
    data class HttpError(
        val code: Int,
        val body: String = "",
        val message: String = "HTTP $code"
    ) : NetworkResult<Nothing>()

    /** Transport-level failure: timeout, DNS, SSL, connectivity loss. */
    data class TransportError(
        val reason: String,
        val exception: Throwable? = null
    ) : NetworkResult<Nothing>()

    // ── Convenience accessors ─────────────────────────────────────────

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = !isSuccess

    val isRetryable: Boolean get() = when (this) {
        is Success        -> false
        is HttpError      -> code >= 500 || code == 429
        is TransportError -> true
    }

    val isConnectivityLoss: Boolean get() = when (this) {
        is Success        -> false
        is HttpError      -> false
        is TransportError -> true
    }

    val statusLabel: String get() = when (this) {
        is Success        -> "online"
        is TransportError -> "offline"
        is HttpError -> when {
            code == 429        -> "rate_limited"
            code in 400..499   -> "blocked"
            code >= 500        -> "server_error"
            else               -> "unknown"
        }
    }

    companion object {

        /**
         * Classify any [Throwable] into a [NetworkResult].
         *
         * This is the single entry point for exception → result mapping.
         * Never parse exception strings in business logic.
         */
        fun fromException(e: Throwable): NetworkResult<Nothing> {
            return when (e) {
                is SocketTimeoutException ->
                    TransportError("Connection timed out", e)
                is UnknownHostException ->
                    TransportError("Unable to resolve host — no internet?", e)
                is ConnectException ->
                    TransportError("Connection refused", e)
                is SSLException ->
                    TransportError("SSL handshake failed", e)

                // 1. Check for your custom TimetableApiException first
                is TimetableApiException ->
                    HttpError(code = e.httpCode, body = e.body, message = e.message ?: "API Error ${e.httpCode}")

                // 2. Check for standard Retrofit HTTP exceptions (if you ever migrate to Retrofit)
                is HttpException ->
                    HttpError(code = e.code(), message = e.message())

                else -> {
                    // 3. Fallback for generic exceptions.
                    // Instead of brittle Regex, look for specific string patterns
                    // only if it's an IOException or generic Exception.
                    val msg = e.message ?: ""
                    when {
                        msg.contains("429", ignoreCase = true) ||
                                msg.contains("too many requests", ignoreCase = true) ->
                            HttpError(code = 429, message = msg)
                        else ->
                            TransportError("Unexpected Error: $msg", e)
                    }
                }
            }
        }

        fun fromHttpCode(code: Int, body: String = ""): NetworkResult<Nothing> {
            return when {
                code in 200..299 -> error("fromHttpCode should not be called for 2xx")
                code == 429      -> HttpError(code = 429, body = body, message = "Rate limited (429)")
                code in 400..499 -> HttpError(code = code, body = body, message = "Client error $code")
                code >= 500      -> HttpError(code = code, body = body, message = "Server error $code")
                else             -> HttpError(code = code, body = body, message = "Unexpected status $code")
            }
        }
    }
}