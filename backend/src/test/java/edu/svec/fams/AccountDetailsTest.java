package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The Account page's details: faculty, HoD and Principal may keep their contact number; the administrator has none. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDetailsTest {

    @Autowired MockMvc mvc;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    FamsUserPrincipal faculty, hod, principal, admin;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        hod = db.hod("hod.cse@test.edu", "CSE");
        principal = db.user("principal@test.edu", Role.PRINCIPAL);
        admin = db.user("admin@test.edu", Role.ADMIN);
    }

    private ResultActions read(FamsUserPrincipal who) throws Exception {
        return mvc.perform(get("/api/auth/account").with(user(who)));
    }

    private ResultActions save(FamsUserPrincipal who, String number) throws Exception {
        return mvc.perform(put("/api/auth/account").with(user(who)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"contactNo\":" + (number == null ? "null" : "\"" + number + "\"") + "}"));
    }

    @Test
    void facultyKeepTheirNumberOnTheirProfile() throws Exception {
        read(faculty).andExpect(status().isOk()).andExpect(jsonPath("$.editable").value(true));
        save(faculty, " 98480 12345 ").andExpect(status().isOk()).andExpect(jsonPath("$.contactNo").value("98480 12345"));
        read(faculty).andExpect(jsonPath("$.contactNo").value("98480 12345"));
        String stored = jdbc.sql("SELECT contact_no FROM faculty_profiles WHERE user_id = ?").param(faculty.id()).query(String.class).single();
        assertEquals("98480 12345", stored);
    }

    @Test
    void hodAndPrincipalKeepTheirNumberOnTheAccount() throws Exception {
        for (FamsUserPrincipal who : new FamsUserPrincipal[] {hod, principal}) {
            save(who, "+91 98765 43210").andExpect(status().isOk());
            read(who).andExpect(jsonPath("$.editable").value(true)).andExpect(jsonPath("$.contactNo").value("+91 98765 43210"));
        }
    }

    @Test
    void aBlankNumberClearsItAndNonsenseIsRefused() throws Exception {
        save(hod, "9876543210").andExpect(status().isOk());
        save(hod, "  ").andExpect(status().isOk()).andExpect(jsonPath("$.contactNo").doesNotExist());
        save(hod, "call me").andExpect(status().isBadRequest());
        save(hod, "12").andExpect(status().isBadRequest());
    }

    @Test
    void theAdministratorHasNothingToEdit() throws Exception {
        read(admin).andExpect(status().isOk()).andExpect(jsonPath("$.editable").value(false));
        save(admin, "9876543210").andExpect(status().isForbidden());
    }

    @Test
    void changingTheNumberIsLoggedWithoutTheNumber() throws Exception {
        save(principal, "9876543210").andExpect(status().isOk());
        Integer logged = jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'CONTACT_UPDATED' AND entity_id = ?")
                .param(principal.id()).query(Integer.class).single();
        assertEquals(1, logged);
    }
}
