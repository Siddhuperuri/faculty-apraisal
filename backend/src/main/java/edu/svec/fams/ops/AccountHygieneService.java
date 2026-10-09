package edu.svec.fams.ops;

import edu.svec.fams.common.ApiException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an administrator should tidy among the accounts: ones never used, ones still on the old password, ones that look
 * like the same person twice, ones missing what their role needs, and ones nobody has signed in to for a long time (the
 * departed, or duplicates). Nothing here changes anything; disabling is done on the Accounts page.
 */
@Service
public class AccountHygieneService {

    public static final int MIN_DAYS = 7;
    public static final int MAX_DAYS = 1825;

    public record Person(long id, String name, String email, String role) {}

    /** Accounts that share a value (an employee ID or a contact number) once case, spaces and punctuation are ignored. */
    public record Duplicate(String value, List<Person> accounts) {}

    /** An account missing something its role needs. */
    public record Gap(Person account, String problem) {}

    public record Hygiene(int neverSignedIn, int temporaryPassword, int disabled, List<Duplicate> duplicateEmployeeIds,
                          List<Duplicate> duplicateContacts, List<Gap> missingAssignments) {}

    public record Inactive(long id, String name, String email, String role, String department, OffsetDateTime lastLoginAt,
                           OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;

    public AccountHygieneService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Hygiene hygiene() {
        int never = count("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE' AND last_login_at IS NULL");
        int temporary = count("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE' AND must_change_password = TRUE");
        int disabled = count("SELECT COUNT(*) FROM users WHERE status <> 'ACTIVE'");

        // An account's employee ID and contact number are on the account and, for faculty, on the profile too: count each account once.
        Map<Long, Person> people = new LinkedHashMap<>();
        Map<String, Map<Long, Person>> ids = new TreeMap<>();
        Map<String, Map<Long, Person>> contacts = new TreeMap<>();
        jdbc.sql("""
                SELECT u.id, COALESCE(u.name, fp.name, u.email) AS name, u.email, u.role,
                       u.employee_id AS uid, fp.employee_id AS fid, u.contact_no AS ucontact, fp.contact_no AS fcontact
                FROM users u LEFT JOIN faculty_profiles fp ON fp.user_id = u.id
                WHERE u.status = 'ACTIVE' AND u.role IN ('FACULTY','HOD','PRINCIPAL','DIRECTOR','ADMIN')""")
                .query((rs, n) -> {
                    Person p = new Person(rs.getLong("id"), rs.getString("name"), rs.getString("email"), rs.getString("role"));
                    people.put(p.id(), p);
                    for (String id : new String[] {rs.getString("uid"), rs.getString("fid")}) {
                        String key = idKey(id);
                        if (key != null) ids.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(p.id(), p);
                    }
                    for (String c : new String[] {rs.getString("ucontact"), rs.getString("fcontact")}) {
                        String key = contactKey(c);
                        if (key != null) contacts.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(p.id(), p);
                    }
                    return null;
                }).list();

        List<Gap> gaps = new ArrayList<>();
        jdbc.sql("""
                SELECT u.id, COALESCE(u.name, u.email) AS name, u.email, u.role,
                       CASE
                         WHEN u.role = 'FACULTY' AND fp.id IS NULL THEN 'No department or designation is set'
                         WHEN u.role = 'FACULTY' AND d.active = FALSE THEN CONCAT('Department ', d.code, ' is closed')
                         WHEN u.role = 'HOD' AND NOT EXISTS (SELECT 1 FROM hod_assignments h WHERE h.user_id = u.id) THEN 'No department is assigned'
                         WHEN u.role = 'HOD' AND NOT EXISTS (SELECT 1 FROM hod_assignments h JOIN departments hd ON hd.id = h.department_id
                                                               WHERE h.user_id = u.id AND hd.active = TRUE) THEN 'Every assigned department is closed'
                       END AS problem
                FROM users u
                LEFT JOIN faculty_profiles fp ON fp.user_id = u.id
                LEFT JOIN departments d ON d.id = fp.department_id
                WHERE u.status = 'ACTIVE' AND u.role IN ('FACULTY','HOD')
                ORDER BY u.role, name""")
                .query((rs, n) -> {
                    String problem = rs.getString("problem");
                    if (problem != null) {
                        gaps.add(new Gap(new Person(rs.getLong("id"), rs.getString("name"), rs.getString("email"), rs.getString("role")), problem));
                    }
                    return null;
                }).list();

        return new Hygiene(never, temporary, disabled, duplicates(ids), duplicates(contacts), gaps);
    }

    /** Active accounts nobody has signed in to for at least {@code days} days (counted from creation if never). */
    @Transactional(readOnly = true)
    public List<Inactive> inactive(int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw ApiException.badRequest("Choose a period between " + MIN_DAYS + " days and " + MAX_DAYS + " days.");
        }
        return jdbc.sql("""
                SELECT u.id, COALESCE(u.name, fp.name, u.email) AS name, u.email, u.role, d.code AS dept, u.last_login_at, u.created_at
                FROM users u
                LEFT JOIN faculty_profiles fp ON fp.user_id = u.id
                LEFT JOIN departments d ON d.id = fp.department_id
                WHERE u.status = 'ACTIVE'
                  AND COALESCE(u.last_login_at, u.created_at) < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? DAY)
                ORDER BY COALESCE(u.last_login_at, u.created_at), name""")
                .param(days)
                .query((rs, n) -> new Inactive(rs.getLong("id"), rs.getString("name"), rs.getString("email"), rs.getString("role"),
                        rs.getString("dept"), rs.getObject("last_login_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private static List<Duplicate> duplicates(Map<String, Map<Long, Person>> byKey) {
        List<Duplicate> out = new ArrayList<>();
        byKey.forEach((key, accounts) -> {
            if (accounts.size() > 1) out.add(new Duplicate(key, List.copyOf(accounts.values())));
        });
        return out;
    }

    /** Letters and digits only, in capitals: "emp-001", "EMP 001" and "Emp001" are the same ID. */
    static String idKey(String id) {
        if (id == null) return null;
        String key = id.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    /** The last ten digits, so "+91 98765 43210" and "9876543210" match. */
    static String contactKey(String contact) {
        if (contact == null) return null;
        String digits = contact.replaceAll("\\D", "");
        if (digits.length() < 7) return null;
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }
}
