package edu.svec.fams.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The unauthenticated "forgot password" endpoints (see {@link PasswordResetService}). */
@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) { this.service = service; }

    /** Body {@code {"email": "..."}}. The answer is the same whether or not the address has an account. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> forgot(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Object email = body.get("email");
        service.request(email instanceof String s ? s : null, request.getRemoteAddr());
        return Map.of("message", "If that address belongs to an account, a link to choose a new password has been sent to it.");
    }

    /** Body {@code {"token": "...", "newPassword": "..."}}. */
    @PostMapping("/reset-password")
    public Map<String, String> reset(@RequestBody Map<String, Object> body) {
        Object token = body.get("token");
        Object password = body.get("newPassword");
        service.reset(token instanceof String t ? t : null, password instanceof String p ? p : null);
        return Map.of("message", "Your password has been changed. Please sign in.");
    }
}
