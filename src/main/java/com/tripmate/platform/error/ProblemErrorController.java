package com.tripmate.platform.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.net.URI;

/**
 * Replaces Spring Boot's BasicErrorController for errors that never reach
 * {@link GlobalExceptionHandler}: an exception thrown by a servlet filter,
 * or a {@code response.sendError(...)}. The container forwards those to
 * /error, and without this they would come back in Boot's own
 * timestamp/status/error/path shape instead of a ProblemDetail.
 *
 * <p>A plain {@code @Controller} rather than {@code @RestController}, so the
 * /api/v1 prefix does not apply and the container's forward to /error lands
 * here.
 */
@Controller
@RequestMapping("/error")
public class ProblemErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorController.class);

    @RequestMapping
    public ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        if (status.is5xxServerError()
                && request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure) {
            log.error("Request failed outside the MVC handler on {}",
                    request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI), failure);
        }

        ProblemDetail problem = ApiProblems.forStatus(status);
        if (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String path) {
            problem.setInstance(toUri(path));
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        if (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code) {
            HttpStatus status = HttpStatus.resolve(code);
            if (status != null && status.isError()) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static URI toUri(String path) {
        try {
            return URI.create(path);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
