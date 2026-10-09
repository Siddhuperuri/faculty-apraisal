package edu.svec.fams.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes the API's error body, {@code {"message": ...}}, from a servlet filter. Filters run outside the MVC exception
 * handling ({@link GlobalExceptionHandler}), so they need their own way to answer in the same shape; this is it, so the
 * shape and the encoding are decided in one place.
 */
public final class ErrorResponses {
    private ErrorResponses() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        write(response, status, message, null);
    }

    /** @param code a machine-readable reason the page can act on, or null */
    public static void write(HttpServletResponse response, HttpStatus status, String message, String code) throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("message", message);
        if (code != null) body.put("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        JSON.writeValue(response.getWriter(), body);
    }
}
