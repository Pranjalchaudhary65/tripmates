package com.tripmate.platform.error;

import com.tripmate.platform.logging.CorrelationId;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.util.List;
import java.util.Map;

/**
 * The one place an error response body is built. Title and detail come only
 * from the fixed strings below, never from an exception message, so nothing
 * a framework or driver puts in a message (SQL, hostnames, class names) can
 * reach a client. The correlation ID is the only link between a response and
 * the full server-side log entry.
 */
public final class ApiProblems {

    public static final String CORRELATION_ID = "correlationId";

    public static final String ERRORS = "errors";

    public static final String CODE = "code";

    private static final Map<Integer, String> DETAILS = Map.ofEntries(
            Map.entry(400, "The request is malformed or failed validation."),
            Map.entry(401, "Authentication is required to access this resource."),
            Map.entry(403, "You do not have permission to perform this action."),
            Map.entry(404, "The requested resource was not found."),
            Map.entry(405, "This HTTP method is not supported for this resource."),
            Map.entry(406, "None of the requested response formats can be produced."),
            Map.entry(409, "The request conflicts with the current state of the resource."),
            Map.entry(413, "The request body is too large."),
            Map.entry(415, "This content type is not supported."),
            Map.entry(429, "Too many requests. Try again later."),
            Map.entry(503, "The service is temporarily unavailable. Try again later."));

    private static final String CLIENT_ERROR = "The request could not be processed.";

    private static final String SERVER_ERROR =
            "An unexpected error occurred. Quote the correlation ID if you contact support.";

    private ApiProblems() {
    }

    public static ProblemDetail forStatus(HttpStatusCode status) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detailFor(status));
        problem.setTitle(titleFor(status));
        String correlationId = CorrelationId.current();
        if (correlationId != null) {
            problem.setProperty(CORRELATION_ID, correlationId);
        }
        return problem;
    }

    /**
     * A field-level type is also listed under {@code errors}, in the same
     * shape as a Bean Validation failure, so a client renders it next to the
     * form input without a second code path.
     */
    public static ProblemDetail forType(ProblemType type) {
        ProblemDetail problem = forStatus(type.status());
        problem.setDetail(type.detail());
        problem.setProperty(CODE, type.code());
        if (type.field() != null) {
            problem.setProperty(ERRORS, List.of(new FieldViolation(type.field(), type.detail())));
        }
        return problem;
    }

    private static String titleFor(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        if (known != null) {
            return known.getReasonPhrase();
        }
        return status.is5xxServerError() ? "Server Error" : "Client Error";
    }

    private static String detailFor(HttpStatusCode status) {
        String detail = DETAILS.get(status.value());
        if (detail != null) {
            return detail;
        }
        return status.is5xxServerError() ? SERVER_ERROR : CLIENT_ERROR;
    }
}
