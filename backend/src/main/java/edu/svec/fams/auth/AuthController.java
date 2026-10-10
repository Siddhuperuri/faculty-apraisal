package edu.svec.fams.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    public record LoginRequest(@NotBlank @Size(max = 190) String email,
                               @NotBlank @Size(max = 200) String password) {}

    public record MeResponse(Long id, String email, Role role, boolean mustChangePassword) {}

    public record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword,
                                        @NotBlank @Size(max = 200) String newPassword) {}

    private static final ResponseEntity<Map<String, String>> BAD_CREDENTIALS = ResponseEntity
            .status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid email or password."));

    /** What may be written to a log as "the account that was tried": something shaped like an e-mail address. */
    private static final Pattern LOGGABLE_EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9.-]{1,120}");

    private final AuthenticationManager authenticationManager;
    private final AccountService accounts;
    private final CsrfTokenRepository csrfTokens;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager,
                          AccountService accounts, CsrfTokenRepository csrfTokens) {
        this.authenticationManager = authenticationManager;
        this.accounts = accounts;
        this.csrfTokens = csrfTokens;
    }

    /**
     * The e-mail field of a failed sign-in, made safe for a log line. People sometimes type their password into the
     * wrong box, and the field is free text, so anything that is not shaped like an address is not written at all.
     */
    static String loggable(String email) {
        return email != null && LOGGABLE_EMAIL.matcher(email).matches() ? email : "(not an e-mail address)";
    }

    /** Lets the frontend obtain the CSRF token before its first state-changing call. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body,
                                   HttpServletRequest request, HttpServletResponse response) {
        String email = body.email().strip().toLowerCase(Locale.ROOT);
        String address = request.getRemoteAddr();

        if (body.password().getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES) {   // longer than BCrypt reads: cannot be anyone's password
            log.warn("Sign-in failed for {} from {}", loggable(email), address);
            return BAD_CREDENTIALS;
        }
        try {
            Authentication auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, body.password()));
            FamsUserPrincipal safe = ((FamsUserPrincipal) auth.getPrincipal()).withoutPassword();

            request.getSession(true);
            request.changeSessionId(); // prevent session fixation
            SecurityContext context = holder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    safe, null, safe.getAuthorities()));
            holder.setContext(context);
            contextRepository.saveContext(context, request, response);
            // Remember which password generation this session belongs to; a later change or reset ends it.
            request.getSession().setAttribute(SessionGuardFilter.VERSION_ATTR, accounts.sessionVersion(safe.id()));
            // A CSRF token handed out before sign-in is not carried into the signed-in session.
            csrfTokens.saveToken(null, request, response);
            accounts.recordLogin(safe.id());
            log.info("Signed in: user {} ({}) from {}", safe.id(), safe.role(), address);
            return ResponseEntity.ok(toMe(safe));
        } catch (InternalAuthenticationServiceException e) {
            throw e; // infrastructure failure (e.g. database down): a server error, not a bad credential
        } catch (AuthenticationException e) {
            log.warn("Sign-in failed for {} from {}", loggable(email), address);
            // Identical response for unknown user, wrong password and disabled account: no enumeration.
            return BAD_CREDENTIALS;
        }
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal FamsUserPrincipal principal) {
        return toMe(principal);
    }

    public record ContactRequest(@Size(max = 40) String contactNo) {}

    /** The details the signed-in user may edit on the Account page (none for the administrator). */
    @GetMapping("/account")
    public AccountService.Details account(@AuthenticationPrincipal FamsUserPrincipal principal) {
        return accounts.details(principal);
    }

    @PutMapping("/account")
    public AccountService.Details updateAccount(@Valid @RequestBody ContactRequest body,
                                                @AuthenticationPrincipal FamsUserPrincipal principal) {
        return accounts.updateContact(principal, body.contactNo());
    }

    /** Changes the signed-in user's password; their other sessions end, this one carries on. */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body,
                                               @AuthenticationPrincipal FamsUserPrincipal principal,
                                               HttpServletRequest request) {
        int version = accounts.changePassword(principal, body.currentPassword(), body.newPassword());
        request.getSession().setAttribute(SessionGuardFilter.VERSION_ATTR, version);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication, HttpServletRequest request,
                                       HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().build();
    }

    private MeResponse toMe(FamsUserPrincipal p) {
        return new MeResponse(p.id(), p.email(), p.role(), accounts.state(p.id()).mustChangePassword());
    }
}
