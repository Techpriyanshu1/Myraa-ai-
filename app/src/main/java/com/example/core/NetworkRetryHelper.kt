package com.example.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Provides structured retry with exponential backoff, coroutine cancellation safety,
 * and sensitive token redaction for logs and Room DB entries.
 */
object NetworkRetryHelper {

    suspend fun <T> withExponentialBackoff(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 400L,
        factor: Double = 2.0,
        shouldRetryException: (Throwable) -> Boolean = {
            it is IOException || it is SocketTimeoutException
        },
        block: suspend (attempt: Int) -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastError: Throwable? = null

        for (attempt in 1..maxAttempts) {
            try {
                return block(attempt)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                if (attempt == maxAttempts || !shouldRetryException(t)) {
                    throw t
                }
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
        throw lastError ?: IllegalStateException("Retry loop exhausted unexpectedly")
    }

    /**
     * Strips accidental API-key-like secrets or auth headers from strings before local logging.
     */
    fun sanitizeSensitiveText(input: String): String {
        if (input.isBlank()) return input
        return input
            .replace(Regex("AIza[0-9A-Za-z\\-_]{20,}"), "[REDACTED_KEY]")
            .replace(Regex("sk_[0-9A-Za-z_]{16,}"), "[REDACTED_KEY]")
            .replace(Regex("(?i)(x-goog-api-key|xi-api-key|authorization|bearer)\\s*[:=]\\s*\\S+"), "$1: [REDACTED]")
    }
}
