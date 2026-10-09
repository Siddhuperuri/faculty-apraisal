package edu.svec.fams.auth;

import edu.svec.fams.common.ErrorResponses;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, CsrfTokenRepository csrfTokens, JdbcClient jdbc) throws Exception {
        http
            .cors(c -> {})
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokens)
                .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenHandler()))
            // After the session is loaded and before authorization: ends sessions of disabled / re-roled /
            // password-changed accounts and holds users with a pending password change.
            .addFilterBefore(new SessionGuardFilter(jdbc), AuthorizationFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            // No "return to where you were" for an API. The default would open a server-side session to remember
            // every request refused for lack of a sign-in, so anyone could fill memory with sessions by just asking.
            .requestCache(c -> c.requestCache(new NullRequestCache()))
            .headers(h -> h
                .frameOptions(f -> f.deny())
                .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=()"))
                // Appraisals and reports are for this site's own pages only: another site can neither
                // embed a response nor keep a handle on a window that shows one.
                .crossOriginOpenerPolicy(o -> o.policy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
                .crossOriginResourcePolicy(r -> r.policy(CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN)))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                // CSRF and role failures are 403 (not "please log in again") with a JSON message.
                .accessDeniedHandler((request, response, ex) -> {
                    // A signed-in person refused an area or a request is worth a line; anonymous noise is not.
                    Authentication who = SecurityContextHolder.getContext().getAuthentication();
                    if (who != null && who.getPrincipal() instanceof FamsUserPrincipal p) {
                        log.warn("Refused: user {} ({}) {} {} ({})", p.id(), p.role(), request.getMethod(),
                                request.getRequestURI(), ex.getClass().getSimpleName());
                    }
                    ErrorResponses.write(response, HttpStatus.FORBIDDEN, "Request rejected. Reload the page and try again.");
                }))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/hod/**").hasRole("HOD")
                .requestMatchers("/api/principal/**").hasRole("PRINCIPAL")
                .requestMatchers("/api/director/**").hasRole("DIRECTOR")
                .requestMatchers("/api/faculty/**").hasRole("FACULTY")
                .anyRequest().authenticated());
        return http.build();
    }

    /** The CSRF token lives in a cookie the page's own script reads back into a header (double submit). */
    @Bean
    CsrfTokenRepository csrfTokenRepository(@Value("${fams.secure-cookies}") boolean secureCookies) {
        if (secureCookies) {
            log.info("Cookies are marked Secure: browsers send them over HTTPS only, so sign-in needs HTTPS.");
        } else {
            log.warn("Cookies are NOT marked Secure (FAMS_SECURE_COOKIES=false or the dev profile). "
                    + "Passwords and sessions can be read on the network unless the site is served over HTTPS.");
        }
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(c -> c.secure(secureCookies).sameSite("Lax").path("/"));
        return repository;
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /**
     * Password sign-in. The account's state is checked <em>after</em> the password rather than before (the framework's
     * default order), so refusing a disabled account takes as long as refusing a wrong password and the timing does not
     * reveal which addresses belong to disabled accounts. Unknown addresses already cost the same (a dummy hash check).
     */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        provider.setPreAuthenticationChecks(user -> {});
        provider.setPostAuthenticationChecks(user -> {
            if (!user.isEnabled()) throw new DisabledException("disabled");
        });
        return new ProviderManager(provider);
    }

    /**
     * Cross-origin access is off unless an origin is named. The normal installation needs none: pages and API share one
     * origin (the reverse proxy, or Next's own proxy in development). FAMS_CORS_ORIGIN is for serving the pages from
     * a different origin than the API, and names exactly one.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${fams.cors-allowed-origin}") String origin) {
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        if (origin.isBlank()) return src;
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(origin.strip()));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "X-Request-Id"));
        c.setExposedHeaders(List.of("X-Request-Id"));
        c.setAllowCredentials(true);
        src.registerCorsConfiguration("/api/**", c);
        return src;
    }
}
