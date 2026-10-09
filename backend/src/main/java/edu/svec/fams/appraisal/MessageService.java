package edu.svec.fams.appraisal;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.ValidationException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Messages from the Head of the Department to the faculty member about an appraisal under review: "I have a query about
 * this, please come and see me." They are the one thing the faculty member is told in words during the review (the
 * comment written with an approval stays hidden from them), and they change nothing about the appraisal: it is not sent
 * back and stays locked. Only the faculty member and the Head of their department can read them. The Head of the
 * Department who wrote one may correct it while the appraisal is still under their review; the faculty member then sees
 * it marked as edited and as unread again, and every earlier wording is kept (append-only) and shown to the Heads of the
 * Department. A message cannot be deleted. The text is kept out of the audit trail, which records only that a message
 * was sent or edited.
 */
@Service
public class MessageService {

    public static final int MAX_LENGTH = 2000;   // matches appraisal_messages.body

    /**
     * @param senderName the Head of the Department's name when the account has one, otherwise null
     * @param editedAt when the text was last changed; null if never
     * @param mine whether the person asking wrote it (so the Head of the Department is offered Edit on their own only)
     * @param earlier the wordings an edit has replaced, oldest first; shown to Heads of the Department only, so empty for
     *                the faculty member (they are told that it was edited, not what it said before)
     */
    public record Message(long id, String senderRole, String senderName, String body, OffsetDateTime sentAt,
                          OffsetDateTime editedAt, OffsetDateTime readAt, boolean mine, List<Version> earlier) {

        Message withEarlier(List<Version> versions) {
            return new Message(id, senderRole, senderName, body, sentAt, editedAt, readAt, mine, versions);
        }
    }

    /** One earlier wording: when it was written and when an edit replaced it. */
    public record Version(String body, OffsetDateTime writtenAt, OffsetDateTime replacedAt) {}

    private record Replaced(long messageId, Version version) {}

    private static final String SELECT = """
            SELECT m.id, m.sender_role, u.name, m.body, m.created_at, m.edited_at, m.read_at, m.sender_id = ?
            FROM appraisal_messages m JOIN users u ON u.id = m.sender_id""";

    private final JdbcClient jdbc;
    private final AppraisalAccess access;
    private final AuditService audit;

    public MessageService(JdbcClient jdbc, AppraisalAccess access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    /** Oldest first. For the faculty member who owns the appraisal and the Head of its department; nobody else. */
    @Transactional(readOnly = true)
    public List<Message> list(long appraisalId, FamsUserPrincipal user) {
        participant(appraisalId, user);
        List<Message> messages = jdbc.sql(SELECT + " WHERE m.appraisal_id = ? ORDER BY m.id")
                .params(user.id(), appraisalId)
                .query((rs, n) -> message(rs))
                .list();
        if (user.role() != Role.HOD) return messages;

        Map<Long, List<Version>> earlier = jdbc.sql("""
                SELECT v.message_id, v.body, v.written_at, v.replaced_at FROM appraisal_message_versions v
                JOIN appraisal_messages m ON m.id = v.message_id WHERE m.appraisal_id = ? ORDER BY v.id""")
                .param(appraisalId)
                .query((rs, n) -> new Replaced(rs.getLong(1),
                        new Version(rs.getString(2), rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class))))
                .list().stream()
                .collect(Collectors.groupingBy(Replaced::messageId, Collectors.mapping(Replaced::version, Collectors.toList())));
        return messages.stream().map(m -> m.withEarlier(earlier.getOrDefault(m.id(), List.of()))).toList();
    }

    private static Message message(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Message(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class),
                rs.getObject(7, OffsetDateTime.class), rs.getBoolean(8), List.of());
    }

    /**
     * The Head of the Department sends a message. Allowed only while they are reviewing the appraisal (after they have
     * begun and before they approve): the row is written only if the appraisal is still in that state at that moment.
     */
    @Transactional
    public Message send(long appraisalId, FamsUserPrincipal user, String text) {
        AppraisalAccess.Core core = headOfDepartment(appraisalId, user);
        String body = checked(text);
        if (core.status() != AppraisalStatus.HOD_REVIEW) {
            throw ApiException.conflict("You can message the faculty member while you are reviewing their appraisal, "
                    + "after you have begun and before you approve it.");
        }
        int written = jdbc.sql("""
                INSERT INTO appraisal_messages (appraisal_id, sender_id, sender_role, body)
                SELECT id, ?, 'HOD', ? FROM appraisals WHERE id = ? AND status = 'HOD_REVIEW'""")
                .params(user.id(), body, appraisalId).update();
        if (written == 0) throw ApiException.conflict("The appraisal was changed by someone else. Please reload.");
        touch(appraisalId);

        audit.record(user.id(), "APPRAISAL_MESSAGE_SENT", "APPRAISAL", appraisalId, null);
        return list(appraisalId, user).getLast();
    }

    /**
     * The Head of the Department who wrote a message corrects its text. Allowed only while the appraisal is still under
     * their review, and only for their own message. If the text really changes, it is marked as edited and as unread
     * again, so the faculty member is shown the new wording; saving the same text changes nothing.
     */
    @Transactional
    public Message edit(long appraisalId, long messageId, FamsUserPrincipal user, String text) {
        AppraisalAccess.Core core = headOfDepartment(appraisalId, user);
        String body = checked(text);
        // Locked, so two edits of one message (two browser tabs) cannot both be built on the same earlier wording.
        var current = jdbc.sql("SELECT sender_id, body FROM appraisal_messages WHERE id = ? AND appraisal_id = ? FOR UPDATE")
                .params(messageId, appraisalId)
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)}).optional()
                .orElseThrow(ApiException::notFound);
        if ((long) current[0] != user.id()) throw ApiException.forbidden("You can change only the messages you wrote.");
        if (core.status() != AppraisalStatus.HOD_REVIEW) {
            throw ApiException.conflict("A message can be changed only while you are reviewing the appraisal, before you approve it.");
        }
        if (body.equals(current[1])) return one(appraisalId, messageId, user);

        // The wording being replaced is kept, in this same transaction: if the update below finds the appraisal has moved on,
        // the exception undoes this too.
        jdbc.sql("""
                INSERT INTO appraisal_message_versions (message_id, body, written_at)
                SELECT id, body, COALESCE(edited_at, created_at) FROM appraisal_messages WHERE id = ?""")
                .param(messageId).update();
        int changed = jdbc.sql("""
                UPDATE appraisal_messages m JOIN appraisals a ON a.id = m.appraisal_id
                SET m.body = ?, m.edited_at = CURRENT_TIMESTAMP, m.read_at = NULL
                WHERE m.id = ? AND m.appraisal_id = ? AND m.sender_id = ? AND a.status = 'HOD_REVIEW'""")
                .params(body, messageId, appraisalId, user.id()).update();
        if (changed == 0) throw ApiException.conflict("The appraisal was changed by someone else. Please reload.");

        touch(appraisalId);
        audit.record(user.id(), "APPRAISAL_MESSAGE_EDITED", "APPRAISAL", appraisalId, null);
        return one(appraisalId, messageId, user);
    }

    /** The faculty member has read their messages; the Head of the Department then sees them as seen. */
    @Transactional
    public int markRead(long appraisalId, FamsUserPrincipal user) {
        if (user.role() != Role.FACULTY) throw ApiException.forbidden("Only the faculty member can mark messages as read.");
        participant(appraisalId, user);
        return jdbc.sql("UPDATE appraisal_messages SET read_at = CURRENT_TIMESTAMP WHERE appraisal_id = ? AND read_at IS NULL")
                .param(appraisalId).update();
    }

    /** A message is activity on the appraisal: its "last updated" time, and so its place in the lists, moves on. */
    private void touch(long appraisalId) {
        jdbc.sql("UPDATE appraisals SET updated_at = CURRENT_TIMESTAMP WHERE id = ?").param(appraisalId).update();
    }

    private Message one(long appraisalId, long messageId, FamsUserPrincipal user) {
        return list(appraisalId, user).stream().filter(m -> m.id() == messageId).findFirst().orElseThrow(ApiException::notFound);
    }

    /** Only a Head of the Department writes; and only the one of this faculty member's department (else 404). */
    private AppraisalAccess.Core headOfDepartment(long appraisalId, FamsUserPrincipal user) {
        if (user.role() != Role.HOD) throw ApiException.forbidden("Only the Head of the Department can write a message.");
        return participant(appraisalId, user);
    }

    /** A message's text, trimmed; refused when empty or longer than a message may be. */
    private static String checked(String text) {
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) throw new ValidationException(Map.of("message", "Write the message first."));
        if (body.length() > MAX_LENGTH) {
            throw new ValidationException(Map.of("message", "The message must be at most " + MAX_LENGTH + " characters."));
        }
        return body;
    }

    /** The appraisal, if this user is the faculty member it belongs to or the Head of its department (else 404 or 403). */
    private AppraisalAccess.Core participant(long appraisalId, FamsUserPrincipal user) {
        if (user.role() != Role.FACULTY && user.role() != Role.HOD) {
            throw ApiException.forbidden("Messages are between the faculty member and their Head of the Department.");
        }
        return access.loadVisible(appraisalId, user);
    }
}
