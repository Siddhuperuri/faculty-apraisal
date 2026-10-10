package edu.svec.fams;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.appraisal.WorkflowAction;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ListAndMetaIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestDb db;
    @Autowired AppraisalService appraisals;

    FamsUserPrincipal f1, f2, otherDeptFaculty, hod, otherDeptHod, principal, admin;
    long a1, a2, a3;

    @BeforeEach
    void setUp() {
        db.reset();
        f1 = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        f2 = db.faculty("f2@test.edu", "E002", "CSE", "PROFESSOR");
        otherDeptFaculty = db.faculty("f3@test.edu", "E003", "ECE", "LECTURER");
        hod = db.hod("hod@test.edu", "CSE");
        otherDeptHod = db.hod("hod2@test.edu", "ECE");
        principal = db.user("p@test.edu", Role.PRINCIPAL);
        admin = db.user("admin@test.edu", Role.ADMIN);
        a1 = appraisals.create(f1);                       // draft
        a2 = appraisals.create(f2);                       // submitted
        appraisals.submit(a2, f2);
        a3 = appraisals.create(otherDeptFaculty);         // other department, submitted
        appraisals.submit(a3, otherDeptFaculty);
    }

    @Test
    void facultySeesOnlyTheirOwnAppraisals() throws Exception {
        mvc.perform(get("/api/appraisals").with(user(f1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) a1))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].academicYear").value("2025-26"));
    }

    @Test
    void hodSeesOnlySubmittedAppraisalsOfTheirOwnDepartment() throws Exception {
        mvc.perform(get("/api/appraisals").with(user(hod)))
                .andExpect(jsonPath("$.length()").value(1))          // a2 only: a1 is a draft, a3 is another dept
                .andExpect(jsonPath("$[0].id").value((int) a2))
                .andExpect(jsonPath("$[0].facultyName").value("Dr. E002"));
        mvc.perform(get("/api/appraisals").with(user(otherDeptHod)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) a3));
    }

    @Test
    void thePrincipalSeesNothingUntilTheHodHasApproved() throws Exception {
        mvc.perform(get("/api/appraisals").with(user(principal))).andExpect(jsonPath("$.length()").value(0));
        appraisals.transition(a2, hod, WorkflowAction.Verb.START, null);
        mvc.perform(get("/api/appraisals").with(user(principal))).andExpect(jsonPath("$.length()").value(0));
        appraisals.transition(a2, hod, WorkflowAction.Verb.APPROVE, null);
        mvc.perform(get("/api/appraisals").with(user(principal))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value((int) a2));
        // the Principal covers every department
        appraisals.transition(a3, otherDeptHod, WorkflowAction.Verb.START, null);
        appraisals.transition(a3, otherDeptHod, WorkflowAction.Verb.APPROVE, null);
        mvc.perform(get("/api/appraisals").with(user(principal))).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void adminHasNoAppraisalList() throws Exception {
        mvc.perform(get("/api/appraisals").with(user(admin))).andExpect(status().isForbidden());
    }

    @Test
    void metaDescribesEverySectionForTheForms() throws Exception {
        mvc.perform(get("/api/sections/meta").with(user(f1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[0].key").value("general-information"))
                .andExpect(jsonPath("$[1].key").value("teaching-courses"))
                .andExpect(jsonPath("$[1].singleton").value(false))
                .andExpect(jsonPath("$[1].fields[?(@.name=='passPercentage')].max").value(100.0))
                .andExpect(jsonPath("$[1].fields[0].name").value("courseCode"))
                .andExpect(jsonPath("$[1].fields[1].name").value("courseRole"))   // beside the course code
                .andExpect(jsonPath("$[1].fields[1].allowed[0]").value("COORDINATOR"))
                .andExpect(jsonPath("$[1].fields[1].allowed[1]").value("INSTRUCTOR"))
                .andExpect(jsonPath("$[1].fields[?(@.name=='courseType')].allowed[0]").value("THEORY"))
                .andExpect(jsonPath("$[1].fields[?(@.name=='courseType')].allowed[1]").value("LAB"))
                .andExpect(jsonPath("$[1].fields[?(@.name=='section')].allowed.length()").value(5))
                .andExpect(jsonPath("$[1].fields[?(@.name=='hoursPerWeek')].allowed.length()").value(6))
                .andExpect(jsonPath("$[1].fields[?(@.name=='hoursPerWeek')].min").value(1.0))
                .andExpect(jsonPath("$[1].fields[?(@.name=='hoursPerWeek')].max").value(6.0))
                .andExpect(jsonPath("$[1].fields[?(@.name=='semester')].allowed.length()").value(12))
                .andExpect(jsonPath("$[1].fields[?(@.name=='courseCode')].maxLength").value(32))
                .andExpect(jsonPath("$[?(@.key=='mentoring-summary')].singleton").value(true))
                .andExpect(jsonPath("$[?(@.key=='fdps')].dateRanges.length()").value(0))
                .andExpect(jsonPath("$[?(@.key=='certifications')].fields[?(@.name=='platformOther')].onlyWhenField").value("platform"))
                .andExpect(jsonPath("$[?(@.key=='certifications')].fields[?(@.name=='platformOther')].onlyWhenEquals").value("OTHER"));
    }

    @Test
    void metaRequiresALogin() throws Exception {
        mvc.perform(get("/api/sections/meta")).andExpect(status().isUnauthorized());
    }
}
