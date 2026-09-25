package com.jobassistant.ai;

/**
 * Raised when an AI provider cannot serve a request (no key, network error, rate limit,
 * malformed response...). Callers catch it and degrade to deterministic behaviour.
 */
public class AiUnavailableException extends RuntimeException {

    private final boolean retryable;

    public AiUnavailableException(String message) {
        this(message, null, false);
    }

    public AiUnavailableException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public AiUnavailableException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** True for transient failures (rate limit, 5xx, network) where retrying may help. */
    public boolean isRetryable() {
        return retryable;
    }

    public static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }
}
