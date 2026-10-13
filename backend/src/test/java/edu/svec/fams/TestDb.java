package edu.svec.fams;

import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.reference.ReferenceData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Test-only helper: wipes mutable data (keeps seeded reference data) and builds users. */
@Component
public class TestDb {
    public static final String PASSWORD = "correct-horse";

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final ReferenceData reference;

    public TestDb(JdbcClient jdbc, PasswordEncoder encoder, ReferenceData reference) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.reference = reference;
    }

    public void reset() {
        // Reference lists: values an administrator added during a test go; switched-off values come back on. (Tests that
        // rename a value put its wording back themselves.)
        jdbc.sql("DELETE FROM reference_values WHERE created_by IS NOT NULL").update();
        // everything is offered again except the semesters beyond 8, which the migration switched off for every department
        jdbc.sql("UPDATE reference_values SET active = TRUE WHERE NOT (list_code = 'SEMESTER' AND CAST(code AS UNSIGNED) > 8)").update();
        reference.refresh();
        // The history tables are append-only via triggers; TRUNCATE is the test-only way to clear them.
        jdbc.sql("TRUNCATE TABLE audit_logs").update();
        jdbc.sql("TRUNCATE TABLE review_actions").update();
        jdbc.sql("TRUNCATE TABLE appraisal_reports").update();
        jdbc.sql("TRUNCATE TABLE appraisal_message_versions").update();
        jdbc.sql("DELETE FROM sign_in_attempts").update();
        for (String t : new String[] {"import_history", "restore_tests", "handover_notes"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        for (String t : new String[] {"appraisal_messages", "appraisal_scores", "appraisals"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        // Remove policy versions, academic years and departments created by tests; keep the seeded reference data.
        // Versions 1 to 5 are seeded (V2/V9, V24, V33, V35 and V37); only the ones tests publish after them are removed.
        String extraPolicies = "SELECT id FROM scoring_policies WHERE version > 5"
                + " OR academic_year_id IN (SELECT id FROM academic_years WHERE name <> '2025-26')";
        jdbc.sql("DELETE FROM scoring_policy_role_marks WHERE policy_id IN (" + extraPolicies + ")").update();
        jdbc.sql("DELETE FROM scoring_policy_components WHERE policy_id IN (" + extraPolicies + ")").update();
        jdbc.sql("DELETE FROM scoring_policy_criteria WHERE policy_id IN (" + extraPolicies + ")").update();
        jdbc.sql("DELETE FROM scoring_policies WHERE version > 5"
                + " OR academic_year_id IN (SELECT id FROM academic_years WHERE name <> '2025-26')").update();
        jdbc.sql("DELETE FROM academic_years WHERE name <> '2025-26'").update();
        // the marks of the institutional roles go back to what the migration set (HoD 5, Associate Dean 3)
        jdbc.sql("DELETE FROM scoring_policy_role_marks").update();
        jdbc.sql("""
                INSERT INTO scoring_policy_role_marks (policy_id, role_code, marks)
                SELECT p.id, r.c, r.m FROM scoring_policies p
                CROSS JOIN (SELECT 'HOD' AS c, 5 AS m UNION ALL SELECT 'ASSOCIATE_DEAN', 3) r
                WHERE p.version IN (4, 5)""").update();
        jdbc.sql("UPDATE academic_years SET active = TRUE").update();
        for (String t : new String[] {"hod_assignments", "dean_assignments", "faculty_profiles", "users"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        jdbc.sql("DELETE FROM departments WHERE code NOT IN ('CE','ME','ECE','EEE','CSE','AIML','BSH','MBA')").update();
        jdbc.sql("UPDATE departments SET active = TRUE").update();
    }

    public FamsUserPrincipal user(String email, Role role) {
        jdbc.sql("INSERT INTO users (email, password_hash, role) VALUES (?,?,?)")
                .params(email, encoder.encode(PASSWORD), role.name()).update();
        long id = jdbc.sql("SELECT id FROM users WHERE email = ?").param(email).query(Long.class).single();
        return new FamsUserPrincipal(id, email, null, role, true);
    }

    public FamsUserPrincipal faculty(String email, String employeeId, String dept, String cadre) {
        FamsUserPrincipal u = user(email, Role.FACULTY);
        jdbc.sql("""
                INSERT INTO faculty_profiles (user_id, employee_id, name, department_id, cadre_id)
                VALUES (?, ?, ?, (SELECT id FROM departments WHERE code = ?), (SELECT id FROM cadres WHERE code = ?))""")
                .params(u.id(), employeeId, "Dr. " + employeeId, dept, cadre).update();
        return u;
    }

    /** Fills in the least an appraisal needs before it may be submitted through the API (see AppraisalService.submitBlockers). */
    public void complete(long appraisalId) {
        jdbc.sql("UPDATE general_information SET contact_no = '9000000000', qualification_specialization = 'M.Tech', "
                + "joining_date_institution = '2015-06-01', joining_date_designation = '2020-06-01' WHERE appraisal_id = ?")
                .param(appraisalId).update();
        jdbc.sql("INSERT INTO action_plan (appraisal_id, plan_text) VALUES (?, 'Strengthen teaching, research and outreach over the next two years.') "
                + "ON DUPLICATE KEY UPDATE plan_text = VALUES(plan_text)").param(appraisalId).update();
        for (int i = 1; i <= 8; i++) {
            jdbc.sql("""
                INSERT INTO teaching_courses (appraisal_id, course_code, course_name, course_type, program, branch, semester, hours_per_week, pass_percentage)
                VALUES (?, 'CS101', 'Intro', 'THEORY', 'B_TECH', 'CSE', 3, 4, 90)""").param(appraisalId).update();
        }
    }

    public FamsUserPrincipal hod(String email, String dept) {
        FamsUserPrincipal u = user(email, Role.HOD);
        jdbc.sql("INSERT INTO hod_assignments (user_id, department_id) VALUES (?, (SELECT id FROM departments WHERE code = ?))")
                .params(u.id(), dept).update();
        return u;
    }

    /**
     * An account of a role that has been withdrawn (DEAN or VICE_PRINCIPAL), as migration V8 leaves one: closed, with
     * its role and password kept for the record. Returns its id.
     */
    public long withdrawnAccount(String email, String role) {
        jdbc.sql("INSERT INTO users (email, password_hash, role, status) VALUES (?,?,?, 'DISABLED')")
                .params(email, encoder.encode(PASSWORD), role).update();
        return jdbc.sql("SELECT id FROM users WHERE email = ?").param(email).query(Long.class).single();
    }

    public void disable(FamsUserPrincipal u) {
        jdbc.sql("UPDATE users SET status = 'DISABLED' WHERE id = ?").param(u.id()).update();
    }
}
