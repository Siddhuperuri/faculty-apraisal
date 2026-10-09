package edu.svec.fams.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Takes the CSRF token from the {@code X-XSRF-TOKEN} header and from nowhere else.
 *
 * <p>The framework's default also accepts it as a request parameter ({@code _csrf}). The token is compared with a
 * cookie, and a cookie can be planted by any other site on the same parent domain (another college service, say).
 * A page there could then submit an ordinary HTML form carrying the matching parameter, and the browser would attach
 * the victim's session, because sites under one parent domain count as the same site. A form cannot set a header, and
 * a script on another origin is not allowed to (no cross-origin access is granted), so requiring the header closes it.
 */
final class HeaderOnlyCsrfTokenHandler extends CsrfTokenRequestAttributeHandler {
    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return request.getHeader(csrfToken.getHeaderName());
    }
}
