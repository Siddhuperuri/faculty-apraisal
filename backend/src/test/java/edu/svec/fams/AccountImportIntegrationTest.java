package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.auth.Role;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Creating many accounts from one CSV file, and the old password every account starts with. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountImportIntegrationTest {

    private static final String HEADING = "Name,College e-mail address,Role,Employee ID,Contact number,Department,Designation (cadre)\n";
    private static final String IMPORT = "/api/admin/users/import";
    private static final String STANDARD = "Srivasavi@123";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    TestHttp http;
    MockHttpSession admin;

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        http = new TestHttp(mvc, json);
        db.user("admin@test.edu", Role.ADMIN);
        admin = http.login("admin@test.edu");
    }

    private JsonNode upload(String csv) throws Exception {
        return http.read(http.postFile(admin, IMPORT, csv.getBytes(StandardCharsets.UTF_8)).andExpect(status().isOk()));
    }

    private int users() {
        return jdbc.sql("SELECT COUNT(*) FROM users").query(Integer.class).single();
    }

    /** The problems reported, as "line:column". */
    private static List<String> problems(JsonNode result) {
        List<String> out = new ArrayList<>();
        result.get("errors").forEach(e -> out.add(e.get("row").asInt() + ":" + e.get("column").asText()));
        return out;
    }

    // ---- a good file ----

    @Test
    void aFileOfEveryRoleCreatesAllTheAccountsWithTheStandardPassword() throws Exception {
        String csv = "﻿" + HEADING
                + "Dr. Asha Rao,Asha.Rao@svec.edu,Faculty,E-101,98765 43210,CSE,Assistant Professor\n"
                + "\"Menon, Ravi\",ravi.menon@svec.edu,faculty,E-102,,Information Technology,Sr. Asst. Prof.\n"
                + "Dr. Hari Kumar,hari.kumar@svec.edu,HoD,H-1,9000000001,\"CSE; ECE\",Professor\n"
                + "Dr. P. Venkat,principal@svec.edu,Principal,P-1,9000000002,,\n"
                + "Kavita,admin2@svec.edu,Administrator,,,,\n";
        JsonNode result = upload(csv);

        assertEquals(5, result.get("created").asInt());
        assertEquals(5, result.get("accounts").asInt());
        assertEquals(0, result.get("errors").size());
        assertEquals(2, result.get("byRole").get("FACULTY").asInt());
        assertEquals(1, result.get("byRole").get("HOD").asInt());
        assertEquals(1, result.get("byRole").get("PRINCIPAL").asInt());
        assertEquals(1, result.get("byRole").get("ADMIN").asInt());
        assertEquals(6, users());

        // the faculty record holds what the file said, department and designation read by code or by name
        var asha = jdbc.sql("""
                SELECT f.name, f.employee_id, f.contact_no, d.code AS dept, c.code AS cadre, u.role, u.must_change_password
                FROM faculty_profiles f JOIN users u ON u.id = f.user_id JOIN departments d ON d.id = f.department_id
                JOIN cadres c ON c.id = f.cadre_id WHERE u.email = 'asha.rao@svec.edu'""").query().singleRow();
        assertEquals("Dr. Asha Rao", asha.get("name"));
        assertEquals("E-101", asha.get("employee_id"));
        assertEquals("98765 43210", asha.get("contact_no"));
        assertEquals("CSE", asha.get("dept"));
        assertEquals("ASST_PROF", asha.get("cadre"));
        var ravi = jdbc.sql("""
                SELECT f.name, d.code AS dept, c.code AS cadre FROM faculty_profiles f JOIN users u ON u.id = f.user_id
                JOIN departments d ON d.id = f.department_id JOIN cadres c ON c.id = f.cadre_id
                WHERE u.email = 'ravi.menon@svec.edu'""").query().singleRow();
        assertEquals("Menon, Ravi", ravi.get("name"));
        assertEquals("CSE", ravi.get("dept"));                     // "Information Technology" is half of the CSE department's name
        assertEquals("SR_ASST_PROF", ravi.get("cadre"));

        // a Head of the Department keeps the name, ID and number on the account and heads both departments
        var hari = jdbc.sql("SELECT name, employee_id, contact_no FROM users WHERE email = 'hari.kumar@svec.edu'").query().singleRow();
        assertEquals("Dr. Hari Kumar", hari.get("name"));
        assertEquals("H-1", hari.get("employee_id"));
        assertEquals("9000000001", hari.get("contact_no"));
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM hod_assignments a JOIN users u ON u.id = a.user_id WHERE u.email = 'hari.kumar@svec.edu'")
                .query(Integer.class).single());

        // the accounts list shows them by name, and the search finds them
        JsonNode list = http.read(http.get(admin, "/api/admin/users?q=hari").andExpect(status().isOk()));
        assertEquals(1, list.size());
        assertEquals("Dr. Hari Kumar", list.get(0).get("name").asText());
        assertEquals("CSE, ECE", list.get(0).get("hodDepartments").asText());

        // everyone signs in with the standard password and is held at the password change
        for (String email : new String[] {"asha.rao@svec.edu", "ravi.menon@svec.edu", "hari.kumar@svec.edu", "principal@svec.edu", "admin2@svec.edu"}) {
            MockHttpSession s = http.login(email, STANDARD);
            http.get(s, "/api/auth/me").andExpect(jsonPath("$.mustChangePassword").value(true));
        }
        assertEquals(5, jdbc.sql("SELECT COUNT(*) FROM users WHERE must_change_password = TRUE AND email <> 'admin@test.edu'").query(Integer.class).single());

        // the audit trail has one entry for the file and one for each account, and no password
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'USERS_IMPORTED'").query(Integer.class).single());
        assertEquals(5, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'USER_CREATED'").query(Integer.class).single());
        for (String meta : jdbc.sql("SELECT COALESCE(CAST(metadata AS CHAR), '') FROM audit_logs").query(String.class).list()) {
            assertFalse(meta.contains(STANDARD), meta);
        }
    }

    @Test
    void theDirectorTechnicalIsARoleInTheFile() throws Exception {
        String csv = HEADING
                + "Dr. S. Babu,director@svec.edu,Director Technical,D-1,9000000003,,\n"
                + "Dr. T. Rao,rao@svec.edu,technical director,D-2,,,\n";
        JsonNode result = upload(csv);
        assertEquals(2, result.get("created").asInt());
        assertEquals(2, result.get("byRole").get("DIRECTOR").asInt());
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'DIRECTOR'").query(Integer.class).single());
    }

    @Test
    void semicolonFilesAndFilesSavedByExcelAsWindows1252AreRead() throws Exception {
        String csv = "Name;College e-mail address;Role;Employee ID;Contact number;Department;Designation (cadre)\n"
                + "René Östlund;rene@svec.edu;Faculty;E-1;;CE;Lecturer\n";
        JsonNode result = http.read(http.postFile(admin, IMPORT, csv.getBytes(Charset.forName("windows-1252"))).andExpect(status().isOk()));
        assertEquals(1, result.get("created").asInt());
        assertEquals("René Östlund", jdbc.sql("SELECT name FROM faculty_profiles").query(String.class).single());
    }

    @Test
    void headingsMayBeWrittenInTheOtherWaysPeopleWriteThem() throws Exception {
        String csv = "name,E-mail,ROLE,emp id,Phone,Dept,Designation\nA Person,a@svec.edu,teacher,E-1,9876543210,eee,asst prof\n";
        assertEquals(1, upload(csv).get("created").asInt());
    }

    // ---- a file with something wrong ----

    @Test
    void oneBadRowCreatesNothingAndEveryProblemIsListedAgainstItsLine() throws Exception {
        db.faculty("taken@svec.edu", "E-TAKEN", "CSE", "ASST_PROF");
        int before = users();
        String csv = HEADING
                + "Fine Person,fine@svec.edu,Faculty,E-1,9876543210,CSE,Professor\n"                    // line 2: good
                + "No Dept,nodept@svec.edu,Faculty,E-2,,,Professor\n"                                    // 3: department missing
                + "Wrong Dept,wrong@svec.edu,Faculty,E-3,,Astrology,Professor\n"                          // 4: unknown department
                + "Wrong Cadre,cadre@svec.edu,Faculty,E-4,,CSE,Chancellor\n"                              // 5: unknown designation
                + "No Id,noid@svec.edu,Faculty,,,CSE,Professor\n"                                         // 6: employee ID missing
                + "Bad Mail,not-an-email,Faculty,E-5,,CSE,Professor\n"                                    // 7: e-mail
                + "Bad Role,role@svec.edu,Janitor,E-6,,CSE,Professor\n"                                   // 8: role
                + "Twice,fine@svec.edu,Faculty,E-7,,CSE,Professor\n"                                      // 9: e-mail repeated in the file
                + "Same Id,sameid@svec.edu,Faculty,E-1,,CSE,Professor\n"                                  // 10: employee ID repeated in the file
                + "Exists,TAKEN@svec.edu,Faculty,E-8,,CSE,Professor\n"                                    // 11: account exists
                + "Id Taken,idtaken@svec.edu,Faculty,E-TAKEN,,CSE,Professor\n"                            // 12: employee ID in use
                + "Excel Number,excel@svec.edu,Faculty,E-9,9.88E+09,CSE,Professor\n"                      // 13: number shortened by Excel
                + "Two Depts,two@svec.edu,Faculty,E-10,,CSE; ECE,Professor\n"                              // 14: faculty have one department
                + "Hod No Dept,hod@svec.edu,HoD,,,,\n"                                                    // 15: HoD needs a department
                + ",noname@svec.edu,Faculty,E-11,,CSE,Professor\n";                                       // 16: name missing
        JsonNode result = upload(csv);

        assertEquals(0, result.get("created").asInt());
        assertEquals(15, result.get("accounts").asInt());
        assertEquals(before, users());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action IN ('USERS_IMPORTED', 'USER_CREATED')").query(Integer.class).single());

        List<String> problems = problems(result);
        for (String expected : new String[] {"3:Department", "4:Department", "5:Designation (cadre)", "6:Employee ID", "7:College e-mail address",
                "8:Role", "9:College e-mail address", "10:Employee ID", "11:College e-mail address", "12:Employee ID", "13:Contact number",
                "14:Department", "15:Department", "16:Name"}) {
            assertTrue(problems.contains(expected), expected + " missing from " + problems);
        }
        assertFalse(problems.stream().anyMatch(p -> p.startsWith("2:")), "the good row was reported: " + problems);
        assertFalse(result.get("moreErrors").asBoolean());
        // the message says what to do
        String unknown = result.get("errors").get(1).get("message").asText();
        assertTrue(unknown.contains("Astrology") && unknown.contains("CSE"), unknown);
    }

    @Test
    void anExistingAccountStopsTheWholeFileEvenWhenItIsTheLastRow() throws Exception {
        db.user("already@svec.edu", Role.PRINCIPAL);
        String csv = HEADING
                + "One,one@svec.edu,Faculty,E-1,,CSE,Lecturer\n"
                + "Two,two@svec.edu,Faculty,E-2,,CSE,Lecturer\n"
                + "Three,already@svec.edu,Faculty,E-3,,CSE,Lecturer\n";
        JsonNode result = upload(csv);
        assertEquals(0, result.get("created").asInt());
        assertEquals(List.of("4:College e-mail address"), problems(result));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM users WHERE email IN ('one@svec.edu', 'two@svec.edu')").query(Integer.class).single());
    }

    @Test
    void aFileThatCannotBeUsedAtAllIsRefusedWithAMessage() throws Exception {
        for (String[] bad : new String[][] {
                {"", "The file is empty."},
                {HEADING, "The file has a heading row but no accounts under it."},
                {"Name,Role\nA,Faculty\n", "Missing: College e-mail address, Employee ID, Contact number, Department, Designation (cadre)."},
                {"Name,Name,College e-mail address,Role,Employee ID,Contact number,Department,Designation\n", "appears twice"},
                {HEADING + "A,a@svec.edu,\"Faculty\n", "never closed"},
        }) {
            String message = http.read(http.postFile(admin, IMPORT, bad[0].getBytes(StandardCharsets.UTF_8))
                    .andExpect(status().isBadRequest())).get("message").asText();
            assertTrue(message.contains(bad[1]), message);
        }
        assertEquals(1, users());
    }

    @Test
    void aFileWithTooManyAccountsIsRefused() throws Exception {
        StringBuilder csv = new StringBuilder(HEADING);
        for (int i = 0; i < 1001; i++) csv.append("P").append(i).append(",p").append(i).append("@svec.edu,Principal,,,,\n");
        String message = http.read(http.postFile(admin, IMPORT, csv.toString().getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())).get("message").asText();
        assertTrue(message.contains("1001") && message.contains("1000"), message);
        assertEquals(1, users());
    }

    @Test
    void aLargeFileIsCreatedInOneGo() throws Exception {
        StringBuilder csv = new StringBuilder(HEADING);
        for (int i = 0; i < 300; i++) {
            csv.append("Faculty ").append(i).append(",f").append(i).append("@svec.edu,Faculty,EMP-").append(i).append(",,ME,Professor\n");
        }
        JsonNode result = upload(csv.toString());
        assertEquals(300, result.get("created").asInt());
        assertEquals(300, jdbc.sql("SELECT COUNT(*) FROM faculty_profiles").query(Integer.class).single());
    }

    @Test
    void onlyAnAdministratorMayImport() throws Exception {
        db.faculty("f@test.edu", "F1", "CSE", "ASST_PROF");
        db.hod("h@test.edu", "CSE");
        String csv = HEADING + "X,x@svec.edu,Principal,,,,\n";
        for (String who : new String[] {"f@test.edu", "h@test.edu"}) {
            http.postFile(http.login(who), IMPORT, csv.getBytes(StandardCharsets.UTF_8)).andExpect(status().isForbidden());
        }
        http.postFile(null, IMPORT, csv.getBytes(StandardCharsets.UTF_8)).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM users WHERE email = 'x@svec.edu'").query(Integer.class).single());
    }

    // ---- the standard password ----

    @Test
    void anAccountMadeByHandAndAResetPasswordStartFromTheSameStandardPassword() throws Exception {
        JsonNode created = http.read(http.post(admin, "/api/admin/users", Map.of("role", "PRINCIPAL", "email", "p2@svec.edu")).andExpect(status().isCreated()));
        assertEquals(STANDARD, created.get("temporaryPassword").asText());
        http.login("p2@svec.edu", STANDARD);

        var f = db.faculty("someone@svec.edu", "S1", "CSE", "ASST_PROF");
        JsonNode reset = http.read(http.post(admin, "/api/admin/users/" + f.id() + "/reset-password", null).andExpect(status().isOk()));
        assertEquals(STANDARD, reset.get("temporaryPassword").asText());
        MockHttpSession s = http.login("someone@svec.edu", STANDARD);
        http.get(s, "/api/auth/me").andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    void thePersonMustChooseAPasswordOfTheirOwnNotTheStandardOne() throws Exception {
        http.postFile(admin, IMPORT, (HEADING + "Pri,pri@svec.edu,Principal,,,,\n").getBytes(StandardCharsets.UTF_8)).andExpect(status().isOk());
        MockHttpSession s = http.login("pri@svec.edu", STANDARD);
        http.post(s, "/api/auth/change-password", Map.of("currentPassword", STANDARD, "newPassword", STANDARD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").value("Choose your own password, not the standard one you were given."));
        http.post(s, "/api/auth/change-password", Map.of("currentPassword", STANDARD, "newPassword", "maple-river-42")).andExpect(status().isNoContent());
        http.login("pri@svec.edu", "maple-river-42");
        assertEquals(401, http.tryLogin("pri@svec.edu", STANDARD).getResponse().getStatus());
    }

    // ---- name, employee ID and contact number of the other roles ----

    @Test
    void aHeadOfDepartmentsNameIdAndNumberCanBeEditedAndStaySearchable() throws Exception {
        db.hod("hod@svec.edu", "CSE");
        long id = jdbc.sql("SELECT id FROM users WHERE email = 'hod@svec.edu'").query(Long.class).single();
        JsonNode detail = http.read(http.put(admin, "/api/admin/users/" + id,
                Map.of("name", "Dr. H. O. D.", "employeeId", "H-77", "contactNo", "9000000099")).andExpect(status().isOk()));
        assertEquals("Dr. H. O. D.", detail.get("row").get("name").asText());
        assertEquals("H-77", detail.get("profile").get("employeeId").asText());
        assertEquals("9000000099", detail.get("profile").get("contactNo").asText());

        // changing only the status leaves them alone
        http.put(admin, "/api/admin/users/" + id, Map.of("status", "DISABLED")).andExpect(status().isOk());
        assertEquals("Dr. H. O. D.", jdbc.sql("SELECT name FROM users WHERE id = ?").param(id).query(String.class).single());

        http.put(admin, "/api/admin/users/" + id, Map.of("contactNo", "call me")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.contactNo").exists());
        db.user("other@svec.edu", Role.PRINCIPAL);
        long other = jdbc.sql("SELECT id FROM users WHERE email = 'other@svec.edu'").query(Long.class).single();
        http.put(admin, "/api/admin/users/" + other, Map.of("employeeId", "H-77")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.employeeId").exists());
    }
}
