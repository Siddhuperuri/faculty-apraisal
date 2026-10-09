package edu.svec.fams.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** 400 with per-field messages, keyed like {@code records[2].passPercentage}. */
public class ValidationException extends ApiException {
    private final Map<String, String> fieldErrors;

    public ValidationException(Map<String, String> fieldErrors) {
        super(HttpStatus.BAD_REQUEST, "Some values are invalid.");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() { return fieldErrors; }
}
