package edu.svec.fams.ecap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the ECAP client from the environment. FAMS_ECAP_BASE_URL (and FAMS_ECAP_API_KEY) turn on the real one;
 * FAMS_ECAP_MODE=stub uses the built-in sample catalogue; with neither, courses cannot be chosen and the application
 * says that ECAP is not configured. The paths and field names of the college's API are settings too:
 * FAMS_ECAP_KEY_HEADER (X-API-Key), FAMS_ECAP_SEARCH_PATH (/courses?search={q}), FAMS_ECAP_CODE_PATH (/courses/{code}),
 * FAMS_ECAP_LIST_FIELD (empty: the response is the array), FAMS_ECAP_CODE_FIELD (courseCode), FAMS_ECAP_NAME_FIELD
 * (courseName), FAMS_ECAP_TIMEOUT_SECONDS (5).
 */
@Configuration
class EcapConfig {

    private static final Logger log = LoggerFactory.getLogger(EcapConfig.class);

    @Bean
    EcapClient ecapClient(ObjectMapper json,
                          @Value("${FAMS_ECAP_BASE_URL:}") String baseUrl,
                          @Value("${FAMS_ECAP_API_KEY:}") String apiKey,
                          @Value("${FAMS_ECAP_MODE:}") String mode,
                          @Value("${FAMS_ECAP_KEY_HEADER:X-API-Key}") String keyHeader,
                          @Value("${FAMS_ECAP_SEARCH_PATH:/courses?search={q}}") String searchPath,
                          @Value("${FAMS_ECAP_CODE_PATH:/courses/{code}}") String codePath,
                          @Value("${FAMS_ECAP_LIST_FIELD:}") String listField,
                          @Value("${FAMS_ECAP_CODE_FIELD:courseCode}") String codeField,
                          @Value("${FAMS_ECAP_NAME_FIELD:courseName}") String nameField,
                          @Value("${FAMS_ECAP_TIMEOUT_SECONDS:5}") int timeoutSeconds) {
        String base = baseUrl.strip().replaceAll("/+$", "");
        if (!base.isEmpty()) {
            log.info("Courses are read from ECAP at {}", base);
            return new HttpEcapClient(new HttpEcapClient.Settings(base, apiKey.strip(), keyHeader.strip(), searchPath.strip(),
                    codePath.strip(), listField.strip(), codeField.strip(), nameField.strip(), timeoutSeconds), json);
        }
        if ("stub".equalsIgnoreCase(mode.strip())) {
            log.warn("ECAP is replaced by a small sample catalogue (FAMS_ECAP_MODE=stub): not for real use.");
            return new StubEcapClient();
        }
        log.warn("FAMS_ECAP_BASE_URL is not set: courses cannot be chosen until ECAP is configured.");
        return new UnavailableEcapClient();
    }
}
