package com.tripmate.compliance.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/** Errors from data requests. Every detail is a fixed sentence. */
public enum ComplianceProblem implements ProblemType {

    INVALID_REQUEST_TYPE(HttpStatus.BAD_REQUEST, "invalid_request_type", "Choose EXPORT or DELETION.", "type"),

    DATA_REQUEST_PENDING(HttpStatus.CONFLICT, "data_request_pending",
            "A request of this kind is already being prepared. It usually takes a few minutes.", null),

    EXPORT_NOT_READY(HttpStatus.CONFLICT, "export_not_ready",
            "This export is not ready, has expired or could not be made. Request a new one.", null);

    private final HttpStatus status;

    private final String code;

    private final String detail;

    private final String field;

    ComplianceProblem(HttpStatus status, String code, String detail, String field) {
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.field = field;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String detail() {
        return detail;
    }

    @Override
    public String field() {
        return field;
    }
}