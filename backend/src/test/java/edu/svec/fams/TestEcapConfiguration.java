package edu.svec.fams;

import edu.svec.fams.ecap.EcapClient;
import edu.svec.fams.ecap.EcapCourse;
import edu.svec.fams.ecap.EcapUnavailableException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * ECAP for the tests: every course code exists, with the name "Course " plus the code, unless a test switches ECAP off to
 * see how the application behaves when it is unavailable.
 */
@Configuration
public class TestEcapConfiguration {

    /** Set by a test to make ECAP unavailable; every test that does so must put it back. */
    public static final AtomicBoolean DOWN = new AtomicBoolean(false);

    /** Codes that ECAP does not have, for the tests of a course that is not in ECAP. */
    public static final String UNKNOWN_PREFIX = "NOPE";

    @Bean
    @Primary
    EcapClient testEcap() {
        return new EcapClient() {
            @Override
            public List<EcapCourse> search(String query, int limit) {
                if (DOWN.get()) throw new EcapUnavailableException("ECAP is not available right now. Please try again in a few minutes.");
                String q = query.toUpperCase(Locale.ROOT);
                return q.startsWith(UNKNOWN_PREFIX) ? List.of() : List.of(new EcapCourse(q, "Course " + q));
            }

            @Override
            public Optional<EcapCourse> find(String code) {
                if (DOWN.get()) throw new EcapUnavailableException("ECAP is not available right now. Please try again in a few minutes.");
                return code.toUpperCase(Locale.ROOT).startsWith(UNKNOWN_PREFIX) ? Optional.empty() : Optional.of(new EcapCourse(code, "Course " + code));
            }
        };
    }
}
