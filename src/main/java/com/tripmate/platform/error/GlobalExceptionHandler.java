package com.tripmate.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps every exception raised while handling a request to an RFC 9457
 * {@link ProblemDetail}. Spring MVC's own exceptions (404, 405, 415,
 * unreadable body, ...) keep their status codes via
 * {@link ResponseEntityExceptionHandler}, but the body Spring built for them
 * is discarded in {@link #createResponseEntity} and rebuilt by
 * {@link ApiProblems}, so no framework wording reaches the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Security exceptions need explicit handlers: without them a
     * {@code @PreAuthorize} denial thrown inside a controller would fall
     * through to the catch-all below and come back as a 500 instead of a 403.
     */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> handleApiProblem(ApiProblemException ex) {
        ProblemType type = ex.type();
        return ResponseEntity.status(type.status()).body(ApiProblems.forType(type));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return validationFailure(violationsOf(ex.getBindingResult()), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                violations.addAll(violationsOf(errors));
            } else {
                String parameter = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(error ->
                        violations.add(new FieldViolation(parameter, error.getDefaultMessage())));
            }
        }
        return validationFailure(violations, headers, status);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        return new ResponseEntity<>(ApiProblems.forStatus(statusCode), headers, statusCode);
    }

    private static ResponseEntity<ProblemDetail> respond(HttpStatus status) {
        return ResponseEntity.status(status).body(ApiProblems.forStatus(status));
    }

    private static ResponseEntity<Object> validationFailure(List<FieldViolation> violations,
                                                            HttpHeaders headers, HttpStatusCode status) {
        ProblemDetail problem = ApiProblems.forStatus(status);
        problem.setProperty(ApiProblems.ERRORS, violations);
        return new ResponseEntity<>(problem, headers, status);
    }

    /**
     * Object-level errors get a null field rather than the binding's object
     * name, which is derived from the DTO class name.
     */
    private static List<FieldViolation> violationsOf(Errors errors) {
        return errors.getAllErrors().stream()
                .map(error -> new FieldViolation(
                        error instanceof FieldError fieldError ? fieldError.getField() : null,
                        error.getDefaultMessage()))
                .toList();
    }
}
