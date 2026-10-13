package edu.svec.fams.admin;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.AccountStatus;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.LoginRateLimiter;
import edu.svec.fams.auth.PasswordResetService;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.ValidationException;
import edu.svec.fams.report.ExportFile;
import edu.svec.fams.report.ExportRequest;
import edu.svec.fams.report.ExportTable;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * What an administrator does to several users at once from the Users list: send each a password reset link, or download
 * their details. Both are decided here on the server from the ids and the caller's role, so changing the ids in the browser
 * gains nothing, and both are audited.
 *
 * <p>A reset link is the one-time, 30 minute link of the "forgot password" flow ({@link PasswordResetService}); no
 * password is generated, shown, e-mailed or logged. The administrator's own account is never included (they use the
 * account page), disabled and closed accounts are skipped, and a user already sent several links recently is skipped too.
 * Requests are limited per administrator.
 */
@Service
public class AdminBulkService {

    /** One request sends at most this many e-mails, which are sent one after another: the page sends longer lists in parts. */
    public static final int MAX_LINKS_PER_REQUEST = 200;
    /** Bulk link requests an administrator may make in one rate-limit window (10 minutes). */
    static final int MAX_LINK_REQUESTS = 12;
    /** Links one user may be sent in a window, shared with their own "forgot password" requests. */
    static final int MAX_LINKS_PER_USER = 3;

    /** A user the request did not send a link to, and why. */
    public record Outcome(long id, String email, String reason) {}

    /**
     * @param requested   distinct users asked for
     * @param sent        links the mail server accepted
     * @param skipped     users left alone (disabled, yourself, unknown, asked too often): see {@code skippedUsers}
     * @param failed      users a link could not be e-mailed to: see {@code failedUsers}
     * @param mailReady   false when this server cannot send e-mail at all (nothing was issued)
     */
    public record LinkResult(int requested, int sent, int skipped, int failed, boolean mailReady,
                             List<Outcome> skippedUsers, List<Outcome> failedUsers) {}

    private static final ZoneId COLLEGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PasswordResetService resets;
    private final LoginRateLimiter limiter;

    public AdminBulkService(JdbcClient jdbc, AuditService audit, PasswordResetService resets, LoginRateLimiter limiter) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.resets = resets;
        this.limiter = limiter;
    }

    // ------------------------------------------------------------------ reset links

    private record Target(long id, String email, String role, String status) {}

    public LinkResult sendResetLinks(FamsUserPrincipal actor, List<Long> ids) {
        if (!limiter.tryAcquire("bulk-links|admin|" + actor.id(), MAX_LINK_REQUESTS).granted()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests to send reset links. Please wait a few minutes.");
        }
        List<Target> found = jdbc.sql("SELECT id, email, role, status FROM users WHERE id IN (:ids) AND deleted_at IS NULL").param("ids", ids)
                .query((rs, n) -> new Target(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        Map<Long, Target> byId = new HashMap<>();
        for (Target t : found) byId.put(t.id(), t);

        boolean mailReady = resets.deliveryConfigured();
        List<Outcome> skipped = new ArrayList<>();
        List<Outcome> failed = new ArrayList<>();
        int sent = 0;
        for (Long id : ids) {
            Target t = byId.get(id);
            if (t == null) {
                skipped.add(new Outcome(id, "", "No such user."));
            } else if (t.id() == actor.id()) {
                skipped.add(new Outcome(id, t.email(), "Your own account: use Account > Change password."));
            } else if (!AccountStatus.isActive(t.status()) || Role.find(t.role()).isEmpty()) {
                skipped.add(new Outcome(id, t.email(), "The account is not active."));
            } else if (!mailReady) {
                failed.add(new Outcome(id, t.email(), "E-mail is not set up on this server."));
            } else if (!limiter.tryAcquire("reset|email|" + t.email().toLowerCase(Locale.ROOT), MAX_LINKS_PER_USER).granted()) {
                skipped.add(new Outcome(id, t.email(), "Already sent several links to this user a moment ago."));
            } else if (resets.sendLink(t.id(), t.email(), actor.id())) {
                sent++;
                audit.recordDetails(actor.id(), "PASSWORD_RESET_LINK_SENT", "USER", t.id(), Map.of("bulk", true));
            } else {
                failed.add(new Outcome(id, t.email(), "The mail server did not accept the message."));
            }
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requested", ids.size());
        details.put("sent", sent);
        details.put("skipped", skipped.size());
        details.put("failed", failed.size());
        details.put("mailReady", mailReady);
        audit.recordDetails(actor.id(), "PASSWORD_RESET_LINKS_BULK", "USER", null, details);
        return new LinkResult(ids.size(), sent, skipped.size(), failed.size(), mailReady, skipped, failed);
    }

    // ------------------------------------------------------------------ users export

    private static final List<String> USER_HEADERS = List.of(
            "S.No.", "Name", "Employee ID", "E-mail", "Role", "Status", "Department", "Designation / Cadre", "Last sign-in",
            "Has chosen own password");

    /**
     * The chosen users as a table: name, employee id, e-mail, role, status, department(s), cadre, last sign-in and whether
     * they have chosen a password of their own. Nothing secret is selected from the database at all: no password hash, token,
     * session counter or attempt count.
     */
    public ExportFile exportUsers(FamsUserPrincipal actor, ExportRequest request) {
        List<Object[]> rows = jdbc.sql("""
                SELECT u.id, COALESCE(fp.name, u.name) AS name, COALESCE(fp.employee_id, u.employee_id) AS employee_id, u.email,
                       u.role, u.status, d.name AS dept, c.name AS cadre, u.last_login_at, u.must_change_password,
                       (SELECT GROUP_CONCAT(d2.code ORDER BY d2.code SEPARATOR ', ')
                          FROM hod_assignments h JOIN departments d2 ON d2.id = h.department_id WHERE h.user_id = u.id) AS hod_depts
                FROM users u
                LEFT JOIN faculty_profiles fp ON fp.user_id = u.id
                LEFT JOIN departments d ON d.id = fp.department_id
                LEFT JOIN cadres c ON c.id = fp.cadre_id
                WHERE u.id IN (:ids) AND u.deleted_at IS NULL""").param("ids", request.ids())
                .query((rs, n) -> new Object[] {rs.getString("name"), rs.getString("employee_id"), rs.getString("email"),
                        rs.getString("role"), rs.getString("status"), rs.getString("dept"), rs.getString("cadre"),
                        rs.getObject("last_login_at", OffsetDateTime.class), rs.getBoolean("must_change_password"),
                        rs.getString("hod_depts")}).list();
        if (rows.size() != request.ids().size()) throw ApiException.notFound();
        if (rows.isEmpty()) throw new ValidationException(Map.of("ids", "Select at least one row."));

        rows = new ArrayList<>(rows);
        rows.sort(Comparator.comparing((Object[] r) -> String.valueOf(r[0] == null ? r[2] : r[0]).toLowerCase(Locale.ROOT)));

        TreeSet<String> departments = new TreeSet<>();
        List<List<Object>> table = new ArrayList<>();
        int serial = 0;
        for (Object[] r : rows) {
            String role = (String) r[3];
            String dept = (String) r[5];
            String hodDepts = (String) r[9];
            String department = "FACULTY".equals(role) ? dept : "HOD".equals(role) ? hodDepts : null;
            if (dept != null) departments.add(dept);
            if (hodDepts != null) departments.addAll(List.of(hodDepts.split(", ")));
            OffsetDateTime last = (OffsetDateTime) r[7];
            List<Object> row = new ArrayList<>();
            row.add(++serial);
            row.add(r[0] == null ? "—" : r[0]);
            row.add(r[1] == null ? "—" : r[1]);
            row.add(r[2]);
            row.add(roleLabel(role));
            row.add(AccountStatus.isActive((String) r[4]) ? "Active" : "Disabled");
            row.add(department == null ? "—" : department);
            row.add(r[6] == null ? "—" : r[6]);
            row.add(last == null ? "Never" : STAMP.format(last.atZoneSameInstant(COLLEGE_ZONE)));
            row.add((Boolean) r[8] ? "No" : "Yes");
            table.add(row);
        }

        String title = "Users - " + table.size() + " selected";
        String subtitle = "Exported by the administrator on " + STAMP.format(ZonedDateTime.now(COLLEGE_ZONE)) + ".";
        byte[] bytes = request.format().render(new ExportTable("Users", title, subtitle, USER_HEADERS, table));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "USERS");
        details.put("format", request.format().name());
        details.put("selected", request.ids().size());
        details.put("departments", new ArrayList<>(departments));
        details.put("actorRole", actor.role().name());
        audit.recordDetails(actor.id(), "EXPORT_USERS", "USER_EXPORT", null, details);
        return new ExportFile("users-" + java.time.LocalDate.now(COLLEGE_ZONE) + "." + request.format().extension(),
                request.format(), bytes, table.size());
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "FACULTY" -> "Faculty";
            case "HOD" -> "Head of the Department";
            case "PRINCIPAL" -> "Principal";
            case "DIRECTOR" -> "Director Technical";
            case "ADMIN" -> "Administrator";
            default -> role;
        };
    }
}
