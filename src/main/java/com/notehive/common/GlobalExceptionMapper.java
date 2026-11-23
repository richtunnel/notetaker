package com.notehive.common;

import io.opentelemetry.api.trace.Span;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps every exception to the unified error body. Internal details are never
 * leaked for 500s; stack traces stay in logs. Trace id comes from the active
 * OpenTelemetry span when present, correlation id is per-request.
 */
@Provider
@Priority(1)
@ApplicationScoped
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionMapper.class);

    public record ErrorBody(String code, String title, int status, String detail,
                            String traceId, String correlationId) {}

    @Override
    public Response toResponse(Exception e) {
        if (e instanceof ApiException api) {
            if (api.status() >= 500) {
                LOG.error("API failure code={} status={}", api.code(), api.status(), api);
            } else if (api.status() >= 400) {
                LOG.debug("API rejection code={} status={} detail={}", api.code(), api.status(), api.getMessage());
            }
            return build(api.status(), api.code().name(), api.getMessage(),
                    api instanceof ApiException.RateLimited rl ? rl.retryAfterSeconds() : null);
        }
        if (e instanceof jakarta.ws.rs.WebApplicationException wae) {
            return Response.fromResponse(wae.getResponse()).type(MediaType.APPLICATION_JSON_TYPE).build();
        }
        LOG.error("Unhandled exception", e);
        return build(500, ErrorCode.INTERNAL_ERROR.name(),
                "Unexpected error. Contact support with the trace id.", null);
    }

    private Response build(int status, String code, String detail, Long retryAfter) {
        String traceId = Span.current().getSpanContext().isValid()
                ? Span.current().getSpanContext().getTraceId()
                : "";
        var body = new ErrorBody(code, titleFor(status), status, detail, traceId,
                java.util.UUID.randomUUID().toString());
        var rb = Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body);
        if (retryAfter != null) {
            rb.header("Retry-After", String.valueOf(retryAfter));
        }
        return rb.build();
    }

    private static String titleFor(int status) {
        return switch (status) {
            case 400 -> "Bad request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not found";
            case 409 -> "Conflict";
            case 413 -> "Payload too large";
            case 422 -> "Unprocessable content";
            case 429 -> "Too many requests";
            default -> "Error";
        };
    }
}
