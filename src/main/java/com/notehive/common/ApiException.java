package com.notehive.common;

/**
 * Base exception for all failures. Carries a machine-readable {@link ErrorCode}
 * and HTTP status; rendered by {@link GlobalExceptionMapper}. Subclasses are
 * nested so the hierarchy is closed and exhaustive.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final int status;

    protected ApiException(ErrorCode code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    protected ApiException(ErrorCode code, int status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public ErrorCode code() { return code; }
    public int status() { return status; }

    public static final class NotFound extends ApiException {
        public NotFound(ErrorCode code, String message) { super(code, 404, message); }
    }

    public static final class Forbidden extends ApiException {
        public Forbidden(String message) { super(ErrorCode.FORBIDDEN, 403, message); }
    }

    public static final class Validation extends ApiException {
        public Validation(ErrorCode code, String message) { super(code, 400, message); }
    }

    public static final class Conflict extends ApiException {
        public Conflict(ErrorCode code, String message) { super(code, 409, message); }
    }

    public static final class TooLarge extends ApiException {
        public TooLarge(String message) { super(ErrorCode.PAYLOAD_TOO_LARGE, 413, message); }
    }

    public static final class RateLimited extends ApiException {
        private final long retryAfterSeconds;
        public RateLimited(String message, long retryAfterSeconds) {
            super(ErrorCode.RATE_LIMITED, 429, message);
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    public static final class Unauthorized extends ApiException {
        public Unauthorized(ErrorCode code, String message) { super(code, 401, message); }
    }

    public static final class Internal extends ApiException {
        public Internal(String message, Throwable cause) {
            super(ErrorCode.INTERNAL_ERROR, 500, message, cause);
        }
    }
}
