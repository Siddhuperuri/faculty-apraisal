package edu.svec.fams.ecap;

import java.util.List;
import java.util.Optional;

/** Used when no ECAP address is configured: every call says so. */
final class UnavailableEcapClient implements EcapClient {
    private static final String MESSAGE =
            "ECAP is not configured on this server, so courses cannot be chosen yet. Please tell your administrator.";

    @Override
    public List<EcapCourse> search(String query, int limit) { throw new EcapUnavailableException(MESSAGE); }

    @Override
    public Optional<EcapCourse> find(String code) { throw new EcapUnavailableException(MESSAGE); }
}
