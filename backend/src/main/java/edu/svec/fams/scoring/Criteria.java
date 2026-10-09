package edu.svec.fams.scoring;

/**
 * The nine criteria of the form's score sheet (item 11 and Annexure A), in the form's order and wording.
 * The codes match scoring_policy_criteria.criterion; a test checks every seeded code is listed here.
 *
 * <p>What a criterion is worth for a cadre, and the components that maximum is made of, are not here: they are the
 * scoring policy (tables scoring_policy_criteria and scoring_policy_components), read through {@link ScoreService}.
 */
public enum Criteria {
    TEACHING_LEARNING("Teaching & Learning", false),
    STUDENT_MENTORING("Student Mentoring, Guidance & Achievements", false),
    FDP_CERTIFICATIONS("FDPs / Certifications", false),
    ADMINISTRATIVE("Administrative, Curriculum & Quality Contributions", false),
    RESEARCH_PUBLICATIONS("Research & Publications", true),
    FUNDED_PROJECTS("Funded Projects / Consultancy", true),
    PATENTS_BOOKS_IPR("Patents, Books & IPR", true),
    OUTREACH("Outreach", true),
    MEMBERSHIPS_AWARDS("Professional Memberships, Awards & Recognitions", true);

    private final String label;
    private final boolean perEntry;

    Criteria(String label, boolean perEntry) {
        this.label = label;
        this.perEntry = perEntry;
    }

    /**
     * B5 to B9 are marked per entry (each paper, project, book, event ...) with no upper limit and the same for every
     * cadre, so they have no maximum. B1 to B4 have a fixed maximum for each cadre.
     */
    public boolean perEntry() { return perEntry; }

    public static boolean isPerEntry(String code) {
        try {
            return valueOf(code).perEntry;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String label() { return label; }

    /** The criterion's number in Part B, as the college's documents cite it: B1 for Teaching & Learning, and so on. */
    public String reference() { return "B" + (ordinal() + 1); }

    /** Position on the score sheet; unknown (future) codes sort last. */
    public static int order(String code) {
        try {
            return valueOf(code).ordinal();
        } catch (IllegalArgumentException e) {
            return Integer.MAX_VALUE;
        }
    }

    public static String labelOf(String code) {
        try {
            return valueOf(code).label;
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    public static String referenceOf(String code) {
        try {
            return valueOf(code).reference();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
