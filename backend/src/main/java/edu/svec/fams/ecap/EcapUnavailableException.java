package edu.svec.fams.ecap;

import edu.svec.fams.common.ApiException;
import org.springframework.http.HttpStatus;

/** ECAP is not configured, cannot be reached, or answered something unusable: shown to the person as "try again later". */
public class EcapUnavailableException extends ApiException {
    public EcapUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
