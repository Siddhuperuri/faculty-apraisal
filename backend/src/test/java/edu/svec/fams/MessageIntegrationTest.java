package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The Head of the Department's message to the faculty member while the appraisal is under review. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessageIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    static final String DECLARATION = "{\"declarationAccepted\":true}";
    static final String ASK = "I have a query about your teaching load. Please come and see me in my office this week.";

    FamsUserPrincipal faculty, otherFaculty, hod, colleagueHod, otherDeptHod, principal, admin;
    long appraisal;

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        otherFaculty = db.faculty("f2@test.edu", "E002", "CSE", "PROFESSOR");
        hod = db.hod("hod@test.edu", "CSE");
        colleagueHod = db.hod("hod3@test.edu", "CSE");          // a second Head of the same department
        otherDeptHod = db.hod("hod2@test.edu", "ECE");
        principal = db.user("p@test.edu", Role.PRINCIPAL);
        admin = db.user("admin@test.edu", Role.ADMIN);

        appraisal = Long.parseLong(call(faculty, "POST", "/api/appraisals", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().replaceAll("\\D+", ""));
        db.complete(appraisal);
        call(faculty, "POST", "/api/appraisals/" + appraisal + "/submit", DECLARATION).andExpect(status().isOk());
    }

    private ResultActions call(FamsUserPrincipal who, String method, String path, Object body) throws Exception {
        var req = request(HttpMethod.valueOf(method), path).with(user(who)).with(csrf().asHeader());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        return mvc.perform(req);
    }

    private String messages() { return "/api/appraisals/" + appraisal + "/messages"; }

    private void beginReview() throws Exception {
        call(hod, "POST", "/api/appraisals/" + appraisal + "/review/start", null).andExpect(status().isOk());
    }

    private JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String statusOf() {
        return jdbc.sql("SELECT status FROM appraisals WHERE id = ?").param(appraisal).query(String.class).single();
    }

    // ---- sending ----

    @Test
    void theHeadOfTheDepartmentCanMessageTheFacultyMemberWhileReviewingAndTheyReadItAndTheSenderSeesItWasSeen() throws Exception {
        beginReview();
        JsonNode sent = read(call(hod, "POST", messages(), Map.of("message", "  " + ASK + "  ")).andExpect(status().isCreated()));
        assertEquals(ASK, sent.get("body").asText());                                  // trimmed
        assertEquals("HOD", sent.get("senderRole").asText());
        assertTrue(sent.get("readAt").isNull());

        // the faculty member sees it, unread
        JsonNode theirs = read(call(faculty, "GET", messages(), null).andExpect(status().isOk()));
        assertEquals(1, theirs.size());
        assertEquals(ASK, theirs.get(0).get("body").asText());
        assertTrue(theirs.get(0).get("readAt").isNull());

        // reading marks it as seen, once
        call(faculty, "POST", messages() + "/read", null).andExpect(status().isOk()).andExpect(jsonPath("$.marked").value(1));
        call(faculty, "POST", messages() + "/read", null).andExpect(status().isOk()).andExpect(jsonPath("$.marked").value(0));

        // and the Head of the Department sees that it was seen
        JsonNode mine = read(call(hod, "GET", messages(), null).andExpect(status().isOk()));
        assertFalse(mine.get(0).get("readAt").isNull());
    }

    @Test
    void aMessageChangesNothingAboutTheAppraisal() throws Exception {
        beginReview();
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isCreated());
        assertEquals("HOD_REVIEW", statusOf());                                           // not returned, not moved
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM review_actions WHERE action NOT IN ('SUBMIT', 'START_HOD_REVIEW')")
                .query(Integer.class).single());
        call(faculty, "GET", "/api/appraisals/" + appraisal, null).andExpect(jsonPath("$.editable").value(true));   // see the resubmission tests

        // the Head of the Department can still approve afterwards
        call(hod, "POST", "/api/appraisals/" + appraisal + "/review/approve", Map.of("comment", "Met and resolved.")).andExpect(status().isOk());
        assertEquals("HOD_APPROVED", statusOf());
    }

    @Test
    void theApprovalCommentStaysHiddenFromTheFacultyMemberEvenThoughMessagesAreShown() throws Exception {
        beginReview();
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isCreated());
        call(hod, "POST", "/api/appraisals/" + appraisal + "/review/approve", Map.of("comment", "A private remark")).andExpect(status().isOk());
        String view = call(faculty, "GET", "/api/appraisals/" + appraisal, null).andReturn().getResponse().getContentAsString();
        assertFalse(view.contains("A private remark"), "the HoD's remark reached the faculty member");
        assertFalse(call(faculty, "GET", messages(), null).andReturn().getResponse().getContentAsString().contains("A private remark"));
    }

    @Test
    void aMessageCanOnlyBeSentBetweenBeginningTheReviewAndApproving() throws Exception {
        // submitted but the review has not begun
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isConflict());
        beginReview();
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isCreated());
        call(hod, "POST", "/api/appraisals/" + appraisal + "/review/approve", null).andExpect(status().isOk());
        // forwarded to the Principal: the Head of the Department's part is over
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isConflict());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM appraisal_messages").query(Integer.class).single());
        // what was said stays readable
        assertEquals(1, read(call(faculty, "GET", messages(), null)).size());
        assertEquals(1, read(call(hod, "GET", messages(), null)).size());
    }

    @Test
    void severalMessagesKeepTheirOrder() throws Exception {
        beginReview();
        for (String text : new String[] {"First", "Second", "Third"}) {
            call(hod, "POST", messages(), Map.of("message", text)).andExpect(status().isCreated());
        }
        JsonNode all = read(call(faculty, "GET", messages(), null));
        assertEquals("First", all.get(0).get("body").asText());
        assertEquals("Third", all.get(2).get("body").asText());
    }

    @Test
    void anEmptyOrTooLongMessageIsRefusedWithAFieldError() throws Exception {
        beginReview();
        for (Object bad : new Object[] {Map.of("message", ""), Map.of("message", "   \n "), Map.of("message", "x".repeat(2001)), Map.of()}) {
            call(hod, "POST", messages(), bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.message").exists());
        }
        call(hod, "POST", messages(), Map.of("message", "x".repeat(2000))).andExpect(status().isCreated());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM appraisal_messages").query(Integer.class).single());
    }

    // ---- who may do what ----

    @Test
    void onlyTheFacultyMemberAndTheirHeadOfDepartmentCanReadMessages() throws Exception {
        beginReview();
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isCreated());
        call(otherFaculty, "GET", messages(), null).andExpect(status().isNotFound());      // someone else's appraisal
        call(otherDeptHod, "GET", messages(), null).andExpect(status().isNotFound());      // another department's
        call(principal, "GET", messages(), null).andExpect(status().isForbidden());
        call(admin, "GET", messages(), null).andExpect(status().isForbidden());
    }

    @Test
    void onlyTheirOwnHeadOfDepartmentCanSend() throws Exception {
        beginReview();
        call(faculty, "POST", messages(), Map.of("message", ASK)).andExpect(status().isForbidden());
        call(otherFaculty, "POST", messages(), Map.of("message", ASK)).andExpect(status().isForbidden());
        call(otherDeptHod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isNotFound());
        call(principal, "POST", messages(), Map.of("message", ASK)).andExpect(status().isForbidden());
        call(admin, "POST", messages(), Map.of("message", ASK)).andExpect(status().isForbidden());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM appraisal_messages").query(Integer.class).single());
    }

    @Test
    void onlyTheFacultyMemberCanMarkMessagesAsRead() throws Exception {
        beginReview();
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isCreated());
        call(hod, "POST", messages() + "/read", null).andExpect(status().isForbidden());
        call(otherFaculty, "POST", messages() + "/read", null).andExpect(status().isNotFound());
        assertNull(jdbc.sql("SELECT read_at FROM appraisal_messages").query().singleRow().get("read_at"));
    }

    @Test
    void aMessageToADraftIsNotPossibleBecauseTheHeadOfDepartmentCannotSeeIt() throws Exception {
        long draft = Long.parseLong(call(otherFaculty, "POST", "/api/appraisals", null).andReturn().getResponse().getContentAsString().replaceAll("\\D+", ""));
        call(hod, "POST", "/api/appraisals/" + draft + "/messages", Map.of("message", ASK)).andExpect(status().isNotFound());
    }

    @Test
    void sendingIsRecordedInTheAuditTrailWithoutTheText() throws Exception {
        beginReview();
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isCreated());
        var rows = jdbc.sql("SELECT actor_id, entity_type, entity_id, metadata FROM audit_logs WHERE action = 'APPRAISAL_MESSAGE_SENT'").query().listOfRows();
        assertEquals(1, rows.size());
        assertEquals(hod.id(), ((Number) rows.get(0).get("actor_id")).longValue());
        assertEquals("APPRAISAL", rows.get(0).get("entity_type"));
        assertEquals(appraisal, ((Number) rows.get(0).get("entity_id")).longValue());
        assertNull(rows.get(0).get("metadata"));
        assertNotNull(rows.get(0).get("entity_id"));
    }

    // ---- editing ----

    private long sendOne(String text) throws Exception {
        return read(call(hod, "POST", messages(), Map.of("message", text)).andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void theHeadOfTheDepartmentCanCorrectTheirMessageAndTheFacultyMemberIsShownItAsEditedAndUnreadAgain() throws Exception {
        beginReview();
        long id = sendOne("Come and see me on Mondya.");
        call(faculty, "POST", messages() + "/read", null).andExpect(status().isOk());          // they have read the first wording

        JsonNode edited = read(call(hod, "PUT", messages() + "/" + id, Map.of("message", "  Come and see me on Monday.  ")).andExpect(status().isOk()));
        assertEquals("Come and see me on Monday.", edited.get("body").asText());
        assertFalse(edited.get("editedAt").isNull());
        assertTrue(edited.get("readAt").isNull());                                             // to be read again
        assertTrue(edited.get("mine").asBoolean());

        JsonNode theirs = read(call(faculty, "GET", messages(), null));
        assertEquals("Come and see me on Monday.", theirs.get(0).get("body").asText());
        assertFalse(theirs.get(0).get("editedAt").isNull());
        assertTrue(theirs.get(0).get("readAt").isNull());
        assertFalse(theirs.get(0).get("mine").asBoolean());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM appraisal_messages").query(Integer.class).single());   // edited in place, not added
        assertEquals("HOD_REVIEW", statusOf());
    }

    @Test
    void savingTheSameTextChangesNothing() throws Exception {
        beginReview();
        long id = sendOne(ASK);
        call(faculty, "POST", messages() + "/read", null).andExpect(status().isOk());
        JsonNode same = read(call(hod, "PUT", messages() + "/" + id, Map.of("message", ASK + "  ")).andExpect(status().isOk()));
        assertTrue(same.get("editedAt").isNull());
        assertFalse(same.get("readAt").isNull());                                              // still seen
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'APPRAISAL_MESSAGE_EDITED'").query(Integer.class).single());
    }

    @Test
    void anEditIsRecordedInTheAuditTrailWithoutTheText() throws Exception {
        beginReview();
        long id = sendOne(ASK);
        call(hod, "PUT", messages() + "/" + id, Map.of("message", "Please come and see me tomorrow.")).andExpect(status().isOk());
        var rows = jdbc.sql("SELECT actor_id, entity_id, metadata FROM audit_logs WHERE action = 'APPRAISAL_MESSAGE_EDITED'").query().listOfRows();
        assertEquals(1, rows.size());
        assertEquals(hod.id(), ((Number) rows.get(0).get("actor_id")).longValue());
        assertEquals(appraisal, ((Number) rows.get(0).get("entity_id")).longValue());
        assertNull(rows.get(0).get("metadata"));
    }

    @Test
    void aMessageCanBeCorrectedOnlyByItsWriterAndOnlyWhileTheReviewIsOpen() throws Exception {
        beginReview();
        long id = sendOne(ASK);
        String path = messages() + "/" + id;
        call(colleagueHod, "PUT", path, Map.of("message", "Changed by a colleague")).andExpect(status().isForbidden());   // not theirs
        call(faculty, "PUT", path, Map.of("message", "Changed by the faculty member")).andExpect(status().isForbidden());
        call(principal, "PUT", path, Map.of("message", "Changed by the Principal")).andExpect(status().isForbidden());
        call(admin, "PUT", path, Map.of("message", "Changed by an administrator")).andExpect(status().isForbidden());
        call(otherDeptHod, "PUT", path, Map.of("message", "Changed by another department")).andExpect(status().isNotFound());
        call(otherFaculty, "PUT", path, Map.of("message", "Changed by someone else")).andExpect(status().isForbidden());
        assertEquals(ASK, jdbc.sql("SELECT body FROM appraisal_messages").query(String.class).single());

        call(hod, "PUT", messages() + "/999999", Map.of("message", "No such message")).andExpect(status().isNotFound());

        // once they approve, the wording is final
        call(hod, "POST", "/api/appraisals/" + appraisal + "/review/approve", null).andExpect(status().isOk());
        call(hod, "PUT", path, Map.of("message", "Too late")).andExpect(status().isConflict());
        assertEquals(ASK, jdbc.sql("SELECT body FROM appraisal_messages").query(String.class).single());
    }

    @Test
    void aCorrectionIsHeldToTheSameRulesAsAMessage() throws Exception {
        beginReview();
        long id = sendOne(ASK);
        for (Object bad : new Object[] {Map.of("message", ""), Map.of("message", "  "), Map.of("message", "x".repeat(2001)), Map.of()}) {
            call(hod, "PUT", messages() + "/" + id, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.message").exists());
        }
        assertEquals(ASK, jdbc.sql("SELECT body FROM appraisal_messages").query(String.class).single());
    }

    @Test
    void aMessageOfAnotherAppraisalCannotBeReachedThroughThisOne() throws Exception {
        beginReview();
        long id = sendOne(ASK);
        long second = Long.parseLong(call(otherFaculty, "POST", "/api/appraisals", null).andReturn().getResponse().getContentAsString().replaceAll("\\D+", ""));
        db.complete(second);
        call(otherFaculty, "POST", "/api/appraisals/" + second + "/submit", DECLARATION).andExpect(status().isOk());
        call(hod, "POST", "/api/appraisals/" + second + "/review/start", null).andExpect(status().isOk());
        call(hod, "PUT", "/api/appraisals/" + second + "/messages/" + id, Map.of("message", "Hijack")).andExpect(status().isNotFound());
        assertEquals(ASK, jdbc.sql("SELECT body FROM appraisal_messages").query(String.class).single());
    }

    // ---- earlier wordings ----

    @Test
    void everyEarlierWordingIsKeptAndTheHeadOfTheDepartmentCanReadThem() throws Exception {
        beginReview();
        long id = sendOne("Come and see me on Mondya.");
        call(hod, "PUT", messages() + "/" + id, Map.of("message", "Come and see me on Monday.")).andExpect(status().isOk());
        JsonNode last = read(call(hod, "PUT", messages() + "/" + id, Map.of("message", "Come and see me on Monday at 10.")).andExpect(status().isOk()));

        JsonNode earlier = last.get("earlier");
        assertEquals(2, earlier.size());                                                   // oldest first
        assertEquals("Come and see me on Mondya.", earlier.get(0).get("body").asText());
        assertEquals("Come and see me on Monday.", earlier.get(1).get("body").asText());
        assertFalse(earlier.get(0).get("writtenAt").isNull());
        assertFalse(earlier.get(0).get("replacedAt").isNull());

        // a colleague Head of the same department can read them too
        JsonNode seenByColleague = read(call(colleagueHod, "GET", messages(), null));
        assertEquals(2, seenByColleague.get(0).get("earlier").size());
        assertFalse(seenByColleague.get(0).get("mine").asBoolean());
        assertEquals("Come and see me on Monday at 10.", seenByColleague.get(0).get("body").asText());
    }

    @Test
    void theFacultyMemberIsToldItWasEditedButNotWhatItSaidBefore() throws Exception {
        beginReview();
        long id = sendOne("A harsher first wording.");
        call(hod, "PUT", messages() + "/" + id, Map.of("message", "A kinder wording.")).andExpect(status().isOk());
        JsonNode theirs = read(call(faculty, "GET", messages(), null));
        assertEquals("A kinder wording.", theirs.get(0).get("body").asText());
        assertFalse(theirs.get(0).get("editedAt").isNull());
        assertEquals(0, theirs.get(0).get("earlier").size());
        assertFalse(call(faculty, "GET", messages(), null).andReturn().getResponse().getContentAsString().contains("harsher"));
    }

    @Test
    void anEditThatIsRefusedOrChangesNothingKeepsNoVersion() throws Exception {
        beginReview();
        long id = sendOne(ASK);
        call(hod, "PUT", messages() + "/" + id, Map.of("message", ASK)).andExpect(status().isOk());              // same text
        call(hod, "PUT", messages() + "/" + id, Map.of("message", "")).andExpect(status().isBadRequest());       // refused
        call(colleagueHod, "PUT", messages() + "/" + id, Map.of("message", "Not theirs")).andExpect(status().isForbidden());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM appraisal_message_versions").query(Integer.class).single());

        call(hod, "POST", "/api/appraisals/" + appraisal + "/review/approve", null).andExpect(status().isOk());
        call(hod, "PUT", messages() + "/" + id, Map.of("message", "Too late")).andExpect(status().isConflict());   // review is over
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM appraisal_message_versions").query(Integer.class).single());
        assertEquals(0, read(call(hod, "GET", messages(), null)).get(0).get("earlier").size());
    }

    @Test
    void theEarlierWordingsCannotBeChangedOrRemovedEvenInTheDatabase() throws Exception {
        beginReview();
        long id = sendOne("First.");
        call(hod, "PUT", messages() + "/" + id, Map.of("message", "Second.")).andExpect(status().isOk());
        for (String sql : new String[] {"UPDATE appraisal_message_versions SET body = 'altered'", "DELETE FROM appraisal_message_versions"}) {
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.sql(sql).update(), sql);
        }
        assertEquals("First.", jdbc.sql("SELECT body FROM appraisal_message_versions").query(String.class).single());
    }

    // ---- the status shown once a message has gone ----

    @Test
    void onceTheHeadOfTheDepartmentHasMessagedTheAppraisalIsShownAsHavingAQueryRaisedUntilItIsApproved() throws Exception {
        call(hod, "GET", "/api/appraisals", null).andExpect(jsonPath("$[0].queryRaised").value(false));      // only submitted
        beginReview();
        call(hod, "GET", "/api/appraisals/" + appraisal, null).andExpect(jsonPath("$.queryRaised").value(false));   // reviewing, nothing said

        sendOne(ASK);
        for (FamsUserPrincipal who : new FamsUserPrincipal[] {hod, faculty}) {
            call(who, "GET", "/api/appraisals", null).andExpect(jsonPath("$[0].queryRaised").value(true));
            call(who, "GET", "/api/appraisals/" + appraisal, null).andExpect(jsonPath("$.queryRaised").value(true))
                    .andExpect(jsonPath("$.status").value("HOD_REVIEW"));                               // the workflow status itself is unchanged
        }
        JsonNode row = null;
        for (JsonNode r : read(call(hod, "GET", "/api/hod/console", null).andExpect(status().isOk())).get("roster")) {
            if (!r.get("appraisalId").isNull() && r.get("appraisalId").asLong() == appraisal) row = r;
        }
        assertNotNull(row);
        assertTrue(row.get("queryRaised").asBoolean());
        assertEquals("NEEDS_HOD", row.get("stage").asText());
        // the colleague who has no message to their name sees the same: it is the appraisal that has a query
        call(colleagueHod, "GET", "/api/appraisals/" + appraisal, null).andExpect(jsonPath("$.queryRaised").value(true));

        // someone else's appraisal is not affected
        long other = Long.parseLong(call(otherFaculty, "POST", "/api/appraisals", null).andReturn().getResponse().getContentAsString().replaceAll("\\D+", ""));
        db.complete(other);
        call(otherFaculty, "POST", "/api/appraisals/" + other + "/submit", DECLARATION).andExpect(status().isOk());
        call(otherFaculty, "GET", "/api/appraisals/" + other, null).andExpect(jsonPath("$.queryRaised").value(false));

        call(hod, "POST", "/api/appraisals/" + appraisal + "/review/approve", null).andExpect(status().isOk());
        call(hod, "GET", "/api/appraisals/" + appraisal, null).andExpect(jsonPath("$.queryRaised").value(false));      // once forwarded, no query is open
    }

    @Test
    void sendingOrEditingAMessageCountsAsActivityOnTheAppraisal() throws Exception {
        beginReview();
        jdbc.sql("UPDATE appraisals SET updated_at = '2020-01-01 00:00:00' WHERE id = ?").param(appraisal).update();
        long id = sendOne(ASK);
        assertTrue(jdbc.sql("SELECT updated_at > '2020-01-01 00:00:00' FROM appraisals WHERE id = ?").param(appraisal).query(Boolean.class).single());

        jdbc.sql("UPDATE appraisals SET updated_at = '2020-01-01 00:00:00' WHERE id = ?").param(appraisal).update();
        call(hod, "PUT", messages() + "/" + id, Map.of("message", "Please come and see me tomorrow.")).andExpect(status().isOk());
        assertTrue(jdbc.sql("SELECT updated_at > '2020-01-01 00:00:00' FROM appraisals WHERE id = ?").param(appraisal).query(Boolean.class).single());
    }

    // ---- the faculty member's answer: correct the appraisal and send it again ----

    private ResultActions saveCourses(int n) throws Exception {
        java.util.List<Map<String, Object>> records = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            records.add(new java.util.LinkedHashMap<>(Map.<String, Object>of("courseCode", "X" + i, "courseName", "Course", "courseType", "THEORY",
                    "program", "B_TECH", "branch", "CSE", "semester", 3, "hoursPerWeek", 4, "passPercentage", 90)));
        }
        return call(faculty, "PUT", "/api/appraisals/" + appraisal + "/sections/teaching-courses", Map.of("records", records));
    }

    @Test
    void untilTheHeadOfTheDepartmentMessagesTheAppraisalStaysLockedForTheFacultyMember() throws Exception {
        saveCourses(1).andExpect(status().isConflict());                     // submitted
        beginReview();
        saveCourses(1).andExpect(status().isConflict());                     // reviewing, but no query yet
        call(faculty, "GET", "/api/appraisals/" + appraisal, null).andExpect(jsonPath("$.editable").value(false));
        call(faculty, "POST", "/api/appraisals/" + appraisal + "/submit", DECLARATION).andExpect(status().isConflict());
    }

    @Test
    void afterAMessageTheFacultyMemberMayEditAndSendItAgainAndTheHeadBeginsTheReviewAfresh() throws Exception {
        beginReview();
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isCreated());

        call(faculty, "GET", "/api/appraisals/" + appraisal, null).andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.queryRaised").value(true)).andExpect(jsonPath("$.status").value("HOD_REVIEW"));
        saveCourses(3).andExpect(status().isOk());                           // the correction
        assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM teaching_courses WHERE appraisal_id = ?").param(appraisal).query(Integer.class).single());

        call(faculty, "POST", "/api/appraisals/" + appraisal + "/submit", DECLARATION).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        assertEquals("SUBMITTED", statusOf());
        // recorded as its own step, and the earlier history is intact
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM review_actions WHERE appraisal_id = ? AND action = 'RESUBMIT' AND from_status = 'HOD_REVIEW' AND to_status = 'SUBMITTED'")
                .param(appraisal).query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM review_actions WHERE appraisal_id = ? AND action = 'SUBMIT'").param(appraisal).query(Integer.class).single());
        // sent again, it is locked once more, and the earlier message no longer holds it open
        saveCourses(1).andExpect(status().isConflict());
        call(faculty, "GET", "/api/appraisals/" + appraisal, null).andExpect(jsonPath("$.editable").value(false)).andExpect(jsonPath("$.queryRaised").value(false));

        beginReview();   // the Head begins afresh: the answered message does not raise a query again
        call(faculty, "GET", "/api/appraisals/" + appraisal, null).andExpect(jsonPath("$.queryRaised").value(false)).andExpect(jsonPath("$.editable").value(false));
        call(hod, "POST", "/api/appraisals/" + appraisal + "/review/approve", null).andExpect(status().isOk());
        assertEquals("HOD_APPROVED", statusOf());
    }

    @Test
    void aNewMessageAfterTheResubmissionOpensTheAppraisalAgain() throws Exception {
        beginReview();
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isCreated());
        call(faculty, "POST", "/api/appraisals/" + appraisal + "/submit", DECLARATION).andExpect(status().isOk());
        beginReview();
        call(hod, "POST", messages(), Map.of("message", "One more thing.")).andExpect(status().isCreated());
        call(faculty, "GET", "/api/appraisals/" + appraisal, null).andExpect(jsonPath("$.editable").value(true)).andExpect(jsonPath("$.queryRaised").value(true));
        saveCourses(2).andExpect(status().isOk());
    }

    @Test
    void onceTheHeadApprovesTheFacultyMemberCanNoLongerEditOrSendItAgain() throws Exception {
        beginReview();
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isCreated());
        call(hod, "POST", "/api/appraisals/" + appraisal + "/review/approve", null).andExpect(status().isOk());
        saveCourses(1).andExpect(status().isConflict());
        call(faculty, "POST", "/api/appraisals/" + appraisal + "/submit", DECLARATION).andExpect(status().isConflict());
        call(faculty, "GET", "/api/appraisals/" + appraisal, null).andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    void sendingItAgainStillNeedsTheDeclarationAndTheEssentials() throws Exception {
        beginReview();
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isCreated());
        call(faculty, "POST", "/api/appraisals/" + appraisal + "/submit", "{\"declarationAccepted\":false}").andExpect(status().isBadRequest());
        jdbc.sql("UPDATE general_information SET contact_no = NULL WHERE appraisal_id = ?").param(appraisal).update();
        call(faculty, "POST", "/api/appraisals/" + appraisal + "/submit", DECLARATION).andExpect(status().isBadRequest());
        assertEquals("HOD_REVIEW", statusOf());
    }

    @Test
    void onlyTheOwnerCanSendItAgain() throws Exception {
        beginReview();
        call(hod, "POST", messages(), Map.of("message", ASK)).andExpect(status().isCreated());
        call(otherFaculty, "POST", "/api/appraisals/" + appraisal + "/submit", DECLARATION).andExpect(status().isNotFound());
        call(hod, "POST", "/api/appraisals/" + appraisal + "/submit", DECLARATION).andExpect(status().isForbidden());
    }
}
