package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * V39 splits teaching experience into whole years and months and turns an ORCID iD typed into the old single text box into a
 * research profile row. The migration has run by the time a test starts (on empty tables), so its row-changing statements are
 * read from the file and run here against values laid out as they were before it.
 */
@SpringBootTest
@ActiveProfiles("test")
class MigrationV39DataTest {

    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;

    private static List<String> statements() throws IOException {
        String sql = new String(new ClassPathResource("db/migration/V39__experience_months_research_profiles.sql").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String statement : sql.split(";")) {
            StringBuilder code = new StringBuilder();
            for (String line : statement.split("\n")) {
                if (!line.strip().startsWith("--")) code.append(line).append('\n');
            }
            String s = code.toString().strip();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    @BeforeEach
    void setUp() {
        db.reset();
    }

    @Test
    void anOldDecimalExperienceBecomesWholeYearsAndMonths() throws Exception {
        // the table as it was before: years held a decimal number, months did not exist
        jdbc.sql("DROP TEMPORARY TABLE IF EXISTS general_information_before").update();
        jdbc.sql("CREATE TEMPORARY TABLE general_information_before (id INT AUTO_INCREMENT PRIMARY KEY, teaching_experience_years DECIMAL(5,1) NULL, "
                + "teaching_experience_months TINYINT UNSIGNED NULL)").update();
        for (String years : new String[] {"6.5", "0", "12.0", "15.9", "40.0", "250.5", "1000.0"}) {
            jdbc.sql("INSERT INTO general_information_before (teaching_experience_years) VALUES (" + years + ")").update();
        }
        jdbc.sql("INSERT INTO general_information_before (teaching_experience_years) VALUES (NULL)").update();

        int ran = 0;
        for (String s : statements()) {
            if (s.startsWith("UPDATE general_information\nSET teaching_experience_")) {
                jdbc.sql(s.replace("general_information", "general_information_before")).update();
                ran++;
            }
        }
        assertEquals(2, ran, "both of the migration's UPDATE statements were found and run");

        List<Map<String, Object>> rows = jdbc.sql("SELECT teaching_experience_years y, teaching_experience_months m FROM general_information_before ORDER BY id")
                .query().listOfRows();
        int[][] expected = {{6, 6}, {0, 0}, {12, 0}, {15, 11}, {40, 0}, {40, 0}, {40, 0}};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], ((Number) rows.get(i).get("y")).intValue(), "years of row " + i);
            assertEquals(expected[i][1], ((Number) rows.get(i).get("m")).intValue(), "months of row " + i);
        }
        assertEquals(null, rows.get(7).get("y"));
        assertEquals(null, rows.get(7).get("m"));
        jdbc.sql("DROP TEMPORARY TABLE general_information_before").update();
    }

    @Test
    void anOrcidTypedIntoTheOldTextBoxBecomesAProfileRowAndOtherTextIsKept() throws Exception {
        FamsUserPrincipal faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        long appraisal = appraisals.create(faculty);
        FamsUserPrincipal other = db.faculty("f2@test.edu", "E002", "CSE", "ASST_PROF");
        long second = appraisals.create(other);
        jdbc.sql("UPDATE general_information SET research_ids = 'ORCID 0000-0001-2345-678X / 5711111111' WHERE appraisal_id = ?").param(appraisal).update();
        jdbc.sql("UPDATE general_information SET research_ids = 'Scopus 5711111111' WHERE appraisal_id = ?").param(second).update();
        jdbc.sql("DELETE FROM research_profiles").update();

        int ran = 0;
        for (String s : statements()) {
            if (s.startsWith("INSERT INTO research_profiles")) {
                jdbc.sql(s).update();
                ran++;
            }
        }
        assertEquals(1, ran);

        List<Map<String, Object>> rows = jdbc.sql("SELECT appraisal_id, platform, profile_id FROM research_profiles").query().listOfRows();
        assertEquals(1, rows.size(), "only the appraisal with an ORCID iD gets a row");
        assertEquals(appraisal, ((Number) rows.get(0).get("appraisal_id")).longValue());
        assertEquals("ORCID", rows.get(0).get("platform"));
        assertEquals("0000-0001-2345-678X", rows.get(0).get("profile_id"));
        // the text itself is not touched
        assertEquals("Scopus 5711111111", jdbc.sql("SELECT research_ids FROM general_information WHERE appraisal_id = ?").param(second)
                .query(String.class).single());
    }
}
