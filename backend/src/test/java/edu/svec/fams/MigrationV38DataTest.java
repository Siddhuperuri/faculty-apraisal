package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.auth.FamsUserPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * V38 rewrites the rows already in {@code outreach}. The migration has run by the time a test starts (on an empty table), so
 * its row-changing statements are read from the file and run here against rows laid out as they were before it: the
 * wording each person had must survive, whatever the role was.
 */
@SpringBootTest
@ActiveProfiles("test")
class MigrationV38DataTest {

    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;

    long appraisal;

    @BeforeEach
    void setUp() {
        db.reset();
        FamsUserPrincipal faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        appraisal = appraisals.create(faculty);
    }

    private void oldRow(String role) {
        jdbc.sql("INSERT INTO outreach (appraisal_id, role, event_activity, organization, event_date) VALUES (?, ?, 'Talk', 'X', '2026-01-10')")
                .params(appraisal, role).update();
        // laid out as before V38: the column held the code and nothing else was filled in
        jdbc.sql("UPDATE outreach SET role_code = NULL, role_other = NULL WHERE appraisal_id = ?").param(appraisal).update();
    }

    /** The statements of the migration that change rows of {@code outreach}, comments removed, in file order. */
    private static List<String> outreachUpdates() throws IOException {
        String sql = new String(new ClassPathResource("db/migration/V38__console_cleanup_delete_user.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String statement : sql.split(";")) {
            StringBuilder code = new StringBuilder();
            for (String line : statement.split("\n")) {
                if (!line.strip().startsWith("--")) code.append(line).append('\n');
            }
            String s = code.toString().strip();
            if (s.startsWith("UPDATE outreach")) out.add(s);
        }
        return out;
    }

    @Test
    void everyEarlierRowKeepsItsWordingAndGetsItsChoice() throws Exception {
        for (String role : new String[] {"RESOURCE_PERSON", "EXTERNAL_EXAMINER", "OTHERS", "SOMETHING_UNKNOWN"}) oldRow(role);

        List<String> updates = outreachUpdates();
        assertTrue(updates.size() >= 3, "the migration's row statements were found: " + updates.size());
        for (String u : updates) jdbc.sql(u).update();

        Map<String, Map<String, Object>> byCode = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.sql("SELECT role, role_code, role_other FROM outreach WHERE appraisal_id = ? ORDER BY id")
                .param(appraisal).query().listOfRows()) {
            byCode.put((String) r.get("role"), r);
        }
        // a standard role: its code is the choice, its label (the new wording where it was renamed) is what is shown
        assertEquals("RESOURCE_PERSON", byCode.get("Resource Person").get("role_code"));
        assertEquals(null, byCode.get("Resource Person").get("role_other"));
        assertEquals("EXTERNAL_EXAMINER", byCode.get("Project External Examiner").get("role_code"));
        // the old closed "Others" is "Other" with the wording it had
        assertEquals("OTHER", byCode.get("Others").get("role_code"));
        assertEquals("Others", byCode.get("Others").get("role_other"));
        // a role the migration does not know is kept as typed text, not lost
        assertEquals("OTHER", byCode.get("SOMETHING_UNKNOWN").get("role_code"));
        assertEquals("SOMETHING_UNKNOWN", byCode.get("SOMETHING_UNKNOWN").get("role_other"));
        assertEquals(4, byCode.size());
    }
}
