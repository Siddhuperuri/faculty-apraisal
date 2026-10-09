package edu.svec.fams.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with a proxy setting that would undo the sign-in limits.
 *
 * <p>The limits are counted per client address. With {@code server.forward-headers-strategy=framework} the address is
 * the <em>first</em> entry of {@code X-Forwarded-For}, which is whatever the client chose to send (Next and most proxies
 * pass an incoming value along), so every guess could claim a new address and never be limited. {@code native} takes the
 * address from the right, past the trusted proxies only, and ignores the header from anyone else.
 */
@Component
public final class ForwardedHeadersCheck {
    private static final Logger log = LoggerFactory.getLogger(ForwardedHeadersCheck.class);

    public ForwardedHeadersCheck(@Value("${server.forward-headers-strategy:none}") String strategy,
                                 @Value("${server.tomcat.remoteip.internal-proxies:}") String trustedProxies) {
        String s = strategy.strip();
        if (s.equalsIgnoreCase("framework")) {
            throw new IllegalStateException("FAMS_FORWARD_HEADERS=framework is not supported: it trusts an X-Forwarded-For "
                    + "value sent by the client, which defeats the sign-in limits. Use FAMS_FORWARD_HEADERS=native "
                    + "(and FAMS_TRUSTED_PROXIES if the reverse proxy is not on this machine). See docs/deployment.md.");
        }
        if (s.equalsIgnoreCase("native")) {
            log.info("Client addresses are taken from X-Forwarded-For when the request comes from a trusted proxy ({}).", trustedProxies);
        } else {
            log.info("Forwarded headers are ignored: every request is attributed to the machine that connected. "
                    + "Behind a reverse proxy set FAMS_FORWARD_HEADERS=native, or all users share the proxy's address for the sign-in limits.");
        }
    }
}
