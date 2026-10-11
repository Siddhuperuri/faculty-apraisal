package edu.svec.fams.ecap;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A small fixed catalogue for trying the application without ECAP (FAMS_ECAP_MODE=stub). It is not a source of real
 * course information.
 */
final class StubEcapClient implements EcapClient {
    private static final List<EcapCourse> COURSES = List.of(
            new EcapCourse("CS101", "Programming for Problem Solving"),
            new EcapCourse("CS201", "Data Structures"),
            new EcapCourse("CS301", "Database Management Systems"),
            new EcapCourse("CS302", "Operating Systems"),
            new EcapCourse("CS401", "Computer Networks"),
            new EcapCourse("EC201", "Digital Logic Design"),
            new EcapCourse("EE201", "Electrical Circuits"),
            new EcapCourse("ME201", "Thermodynamics"),
            new EcapCourse("CE201", "Strength of Materials"),
            new EcapCourse("MB101", "Principles of Management"));

    @Override
    public List<EcapCourse> search(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT);
        return COURSES.stream()
                .filter(c -> c.code().toLowerCase(Locale.ROOT).contains(q) || c.name().toLowerCase(Locale.ROOT).contains(q))
                .limit(limit).toList();
    }

    @Override
    public Optional<EcapCourse> find(String code) {
        return COURSES.stream().filter(c -> c.code().equalsIgnoreCase(code)).findFirst();
    }
}
