package edu.svec.fams.report;

/** Test access to the report's wording of a stored code (ReportLabels itself is package-private). */
public final class ReportLabelsProbe {
    private ReportLabelsProbe() {}

    public static String label(String code) { return ReportLabels.label(code); }

    /** Several application contexts can live in one test run and the lookup is global: point it at the one under test. */
    public static void use(java.util.function.Function<String, String> lookup) { ReportLabels.useLookup(lookup); }
}
