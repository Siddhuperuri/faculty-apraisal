package edu.svec.fams.admin;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.AccountStatus;
import edu.svec.fams.auth.ContactNumber;
import edu.svec.fams.auth.DefaultPassword;
import edu.svec.fams.auth.EmailAddress;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.GeneratedKeys;
import edu.svec.fams.common.ValidationException;
import edu.svec.fams.section.FieldSpec;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account management by the administrator: create users (faculty with their record, HoDs with their departments,
 * the Principal, other administrators), edit them, disable them and reset passwords. A role never changes after
 * creation; make a new account instead. A new or reset account has the old password ({@link DefaultPassword})
 * and must replace it at first sign-in. Passwords are never stored in clear, logged or audited. Many accounts at once
 * come from a CSV file: see {@link AccountImportService}.
 */
@Service
public class AdminUserService {

    public record UserRow(long id, String email, String role, String status, OffsetDateTime lastLoginAt,
                          boolean mustChangePassword, String name, String employeeId, String department,
                          String cadre, String hodDepartments) {}

    public record UserDetail(UserRow row, Long departmentId, Long cadreId, Map<String, Object> profile,
                             List<Long> hodDepartmentIds) {}

    public record IssuedPassword(long id, String email, String role, String temporaryPassword) {}

    /** The faculty record's free-form fields, validated with the same rules as the appraisal form. */
    private static final List<FieldSpec> PROFILE = List.of(
            FieldSpec.text("name", "name", "Name", 120, false),
            FieldSpec.text("employeeId", "employee_id", "Employee ID", 32, false),
            FieldSpec.text("contactNo", "contact_no", "Contact number", 20, false),
            FieldSpec.text("qualification", "qualification", "Qualification", 160, false),
            FieldSpec.text("specialization", "specialization", "Specialization", 160, false),
            FieldSpec.choice("phdStatus", "phd_status", "Ph.D. status", false, "AWARDED", "PURSUING", "NOT_APPLICABLE"),
            FieldSpec.date("joiningDateInstitution", "joining_date_institution", "Date of joining (institution)", false),
            FieldSpec.date("joiningDateDesignation", "joining_date_designation", "Date of joining (present designation)", false),
            FieldSpec.decimal("teachingExperienceYears", "teaching_experience_years", "Teaching experience (years)", 0, 80, 1, false),
            FieldSpec.decimal("industryExperienceYears", "industry_experience_years", "Industry experience (years)", 0, 80, 1, false),
            FieldSpec.decimal("researchExperienceYears", "research_experience_years", "Research experience (years)", 0, 80, 1, false),
            FieldSpec.text("orcid", "orcid", "ORCID", 40, false),
            FieldSpec.text("scopusId", "scopus_id", "Scopus ID", 40, false),
            FieldSpec.text("googleScholarId", "google_scholar_id", "Google Scholar ID", 60, false),
            FieldSpec.text("vidwanId", "vidwan_id", "Vidwan ID", 40, false));

    /** What an account without a faculty record (Head of the Department, Principal, administrator) keeps about the person. */
    private static final List<FieldSpec> PERSON = List.of(
            FieldSpec.text("name", "name", "Name", 120, false),
            FieldSpec.text("employeeId", "employee_id", "Employee ID", 32, false),
            FieldSpec.text("contactNo", "contact_no", "Contact number", 20, false));

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final DefaultPassword defaultPassword;

    public AdminUserService(JdbcClient jdbc, PasswordEncoder encoder, AuditService audit, DefaultPassword defaultPassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.defaultPassword = defaultPassword;
    }

    // ---- reading ----

    @Transactional(readOnly = true)
    public List<UserRow> list(String query, String role) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            String like = "%" + query.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (LOWER(u.email) LIKE ? OR LOWER(COALESCE(fp.name, u.name)) LIKE ?"
                    + " OR LOWER(COALESCE(fp.employee_id, u.employee_id)) LIKE ?)");
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (role != null && !role.isBlank()) {
            if (Arrays.stream(Role.values()).noneMatch(r -> r.name().equals(role))) {
                throw ApiException.badRequest("Unknown role.");
            }
            where.append(" AND u.role = ?");
            params.add(role);
        }
        return jdbc.sql(ROW_SQL + where + " ORDER BY u.id LIMIT 500").params(params)
                .query((rs, n) -> row(rs)).list();
    }

    @Transactional(readOnly = true)
    public UserDetail get(long id) {
        UserRow row = jdbc.sql(ROW_SQL + " WHERE u.id = ?").param(id).query((rs, n) -> row(rs)).optional()
                .orElseThrow(ApiException::notFound);
        Long deptId = null, cadreId = null;
        Map<String, Object> profile = new LinkedHashMap<>();
        if ("FACULTY".equals(row.role())) {
            var rows = jdbc.sql("SELECT * FROM faculty_profiles WHERE user_id = ?").param(id).query().listOfRows();
            if (!rows.isEmpty()) {
                Map<String, Object> fp = rows.get(0);
                deptId = ((Number) fp.get("department_id")).longValue();
                cadreId = ((Number) fp.get("cadre_id")).longValue();
                for (FieldSpec f : PROFILE) {
                    Object v = fp.get(f.column());
                    profile.put(f.name(), v instanceof Date d ? d.toLocalDate() : v);
                }
            }
        } else {
            var rows = jdbc.sql("SELECT name, employee_id, contact_no FROM users WHERE id = ?").param(id).query().listOfRows();
            if (!rows.isEmpty()) {
                for (FieldSpec f : PERSON) profile.put(f.name(), rows.get(0).get(f.column()));
            }
        }
        List<Long> hods = jdbc.sql("SELECT department_id FROM hod_assignments WHERE user_id = ? ORDER BY department_id")
                .param(id).query(Long.class).list();
        return new UserDetail(row, deptId, cadreId, profile, hods);
    }

    private static final String ROW_SQL = """
            SELECT u.id, u.email, u.role, u.status, u.last_login_at, u.must_change_password,
                   COALESCE(fp.name, u.name) AS name, COALESCE(fp.employee_id, u.employee_id) AS employee_id,
                   d.name AS dept, c.name AS cadre,
                   (SELECT GROUP_CONCAT(d2.code ORDER BY d2.code SEPARATOR ', ')
                      FROM hod_assignments h JOIN departments d2 ON d2.id = h.department_id
                     WHERE h.user_id = u.id) AS hod_depts
            FROM users u
            LEFT JOIN faculty_profiles fp ON fp.user_id = u.id
            LEFT JOIN departments d ON d.id = fp.department_id
            LEFT JOIN cadres c ON c.id = fp.cadre_id""";

    private static UserRow row(ResultSet rs) throws SQLException {
        return new UserRow(rs.getLong("id"), rs.getString("email"), rs.getString("role"), rs.getString("status"),
                rs.getObject("last_login_at", OffsetDateTime.class), rs.getBoolean("must_change_password"),
                rs.getString("name"), rs.getString("employee_id"), rs.getString("dept"), rs.getString("cadre"),
                rs.getString("hod_depts"));
    }

    // ---- creating ----

    /** A request that has passed every check: ready to be written. */
    private record Checked(String email, Role role, Map<String, Object> profile, Map<String, Object> person,
                           List<Long> hodDepartments) {}

    /** Creates an account with the old password, which the person must replace at their first sign-in. */
    @Transactional
    public IssuedPassword create(FamsUserPrincipal actor, Map<String, Object> body) {
        Checked checked = check(body);
        return insert(actor, checked, encoder.encode(defaultPassword.value()));
    }

    /**
     * Throws a {@link ValidationException} naming every problem with {@code body} as an account to create, and writes
     * nothing. Used by the CSV import to check the whole file before anything is created.
     */
    void validate(Map<String, Object> body) {
        check(body);
    }

    /**
     * Creates one account as part of the caller's transaction, with a password hash the caller computed once for the
     * whole batch (hashing is deliberately slow, and a batch of accounts all start with the same password).
     */
    IssuedPassword createWithHash(FamsUserPrincipal actor, Map<String, Object> body, String passwordHash) {
        return insert(actor, check(body), passwordHash);
    }

    private Checked check(Map<String, Object> body) {
        Map<String, String> errors = new LinkedHashMap<>();
        Object emailValue = FieldSpec.text("email", "email", "E-mail address", 190, true).normalize(body.get("email"), "email", errors);
        String email = emailValue == null ? null : ((String) emailValue).toLowerCase(Locale.ROOT);
        if (email != null && !EmailAddress.isValid(email)) errors.put("email", "E-mail address must be a valid e-mail address.");
        if (email != null && !errors.containsKey("email")
                && jdbc.sql("SELECT COUNT(*) FROM users WHERE email = ?").param(email).query(Integer.class).single() > 0) {
            errors.put("email", "An account with this e-mail address already exists.");
        }

        Role role = body.get("role") instanceof String s ? Role.find(s).orElse(null) : null;
        if (role == null) errors.put("role", "Choose a role.");

        Map<String, Object> profile = role == Role.FACULTY ? validateFaculty(body, errors, true, null, null) : Map.of();
        Map<String, Object> person = role != null && role != Role.FACULTY ? validatePerson(body, errors) : Map.of();
        List<Long> hodDepartments = role == Role.HOD ? validateDepartments(body, errors, List.of()) : List.of();

        Object employeeId = role == Role.FACULTY ? profile.get("employeeId") : person.get("employeeId");
        if (employeeId != null && !errors.containsKey("employeeId") && employeeIdTaken(role == Role.FACULTY, employeeId)) {
            errors.put("employeeId", "This employee ID is already in use.");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new Checked(email, role, profile, person, hodDepartments);
    }

    private boolean employeeIdTaken(boolean faculty, Object employeeId) {
        String sql = faculty ? "SELECT COUNT(*) FROM faculty_profiles WHERE employee_id = ?" : "SELECT COUNT(*) FROM users WHERE employee_id = ?";
        return jdbc.sql(sql).param(employeeId).query(Integer.class).single() > 0;
    }

    private IssuedPassword insert(FamsUserPrincipal actor, Checked c, String passwordHash) {
        long id;
        try {
            id = GeneratedKeys.insert(jdbc.sql("""
                    INSERT INTO users (email, password_hash, role, name, employee_id, contact_no, must_change_password)
                    VALUES (?,?,?,?,?,?,TRUE)""")
                    .params(c.email(), passwordHash, c.role().name(), c.person().get("name"), c.person().get("employeeId"),
                            c.person().get("contactNo")));
        } catch (DuplicateKeyException e) {
            throw new ValidationException(Map.of("email", "An account with this e-mail address already exists."));
        }

        if (c.role() == Role.FACULTY) insertProfile(id, c.profile());
        for (long d : c.hodDepartments()) {
            jdbc.sql("INSERT INTO hod_assignments (user_id, department_id) VALUES (?,?)").params(id, d).update();
        }
        audit.recordDetails(actor.id(), "USER_CREATED", "USER", id, Map.of("email", c.email(), "role", c.role().name()));
        return new IssuedPassword(id, c.email(), c.role().name(), defaultPassword.value());
    }

    // ---- editing ----

    @Transactional
    public UserDetail update(FamsUserPrincipal actor, long id, Map<String, Object> body) {
        UserDetail existing = get(id);
        UserRow current = existing.row();
        Map<String, String> errors = new LinkedHashMap<>();
        Role role = currentRole(current);

        // A field that is not in the request keeps its current value, so a request may change just one thing
        // (for example only the status). Whatever the result is must be valid as a whole.
        Map<String, Object> profile = Map.of();
        if (role == Role.FACULTY) {
            Map<String, Object> merged = new LinkedHashMap<>();
            existing.profile().forEach((k, v) -> merged.put(k, v instanceof LocalDate d ? d.toString() : v));
            merged.put("departmentId", existing.departmentId());
            merged.put("cadreId", existing.cadreId());
            for (FieldSpec f : PROFILE) {
                if (body.containsKey(f.name())) merged.put(f.name(), body.get(f.name()));
            }
            if (body.containsKey("departmentId")) merged.put("departmentId", body.get("departmentId"));
            if (body.containsKey("cadreId")) merged.put("cadreId", body.get("cadreId"));
            profile = validateFaculty(merged, errors, true, existing.departmentId(), existing.cadreId());
        }
        // The other roles keep a name, an employee ID and a contact number on the account itself; only what is sent changes.
        Map<String, Object> person = new LinkedHashMap<>();
        if (role != Role.FACULTY) {
            for (FieldSpec f : PERSON) {
                if (body.containsKey(f.name())) person.put(f.name(), f.normalize(body.get(f.name()), f.name(), errors));
            }
            if (person.get("contactNo") instanceof String c && !ContactNumber.isValid(c)) errors.putIfAbsent("contactNo", ContactNumber.MESSAGE);
        }
        List<Long> hodDepartments = existing.hodDepartmentIds();
        if (role == Role.HOD && body.containsKey("hodDepartmentIds")) {
            hodDepartments = validateDepartments(body, errors, existing.hodDepartmentIds());
        }

        AccountStatus status = null;
        Object s = body.get("status");
        if (s != null) {
            if (s instanceof String text && Arrays.stream(AccountStatus.values()).anyMatch(v -> v.name().equals(text))) {
                status = AccountStatus.valueOf(text);
            } else {
                errors.put("status", "Status must be ACTIVE or DISABLED.");
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        if (status == AccountStatus.DISABLED && id == actor.id()) {
            throw ApiException.conflict("You cannot disable your own account.");
        }

        if (role == Role.FACULTY) updateProfile(id, profile);
        else updatePerson(id, person);
        if (role == Role.HOD && body.containsKey("hodDepartmentIds")) {
            jdbc.sql("DELETE FROM hod_assignments WHERE user_id = ?").param(id).update();
            for (long d : hodDepartments) {
                jdbc.sql("INSERT INTO hod_assignments (user_id, department_id) VALUES (?,?)").params(id, d).update();
            }
        }
        if (status != null && !status.name().equals(current.status())) {
            jdbc.sql("UPDATE users SET status = ? WHERE id = ?").params(status.name(), id).update();
            audit.recordDetails(actor.id(), status == AccountStatus.DISABLED ? "USER_DISABLED" : "USER_ENABLED", "USER", id,
                    Map.of("email", current.email()));
        }
        audit.recordDetails(actor.id(), "USER_UPDATED", "USER", id, Map.of("email", current.email(), "role", current.role()));
        return get(id);
    }

    /** Sets the password back to the standard one; the user must replace it at next sign-in and all their sessions end. */
    @Transactional
    public IssuedPassword resetPassword(FamsUserPrincipal actor, long id) {
        UserRow u = get(id).row();
        currentRole(u);
        if (id == actor.id()) throw ApiException.conflict("Use Change password for your own account.");
        jdbc.sql("""
                UPDATE users SET password_hash = ?, must_change_password = TRUE,
                       session_version = session_version + 1, password_changed_at = CURRENT_TIMESTAMP
                WHERE id = ?""").params(encoder.encode(defaultPassword.value()), id).update();
        audit.recordDetails(actor.id(), "PASSWORD_RESET", "USER", id, Map.of("email", u.email()));
        return new IssuedPassword(id, u.email(), u.role(), defaultPassword.value());
    }

    /**
     * The account's role, which must be one that still exists. Dean and Vice Principal accounts were closed when those
     * roles were withdrawn; they stay listed for the record and cannot be edited, re-opened or given a password.
     */
    private static Role currentRole(UserRow user) {
        return Role.find(user.role()).orElseThrow(() -> ApiException.conflict(
                "This account's role has been withdrawn. The account is closed and cannot be changed."));
    }

    // ---- name, employee ID and contact number of the accounts that have no faculty record ----

    /** Each may be left out; a contact number given must look like one. */
    private Map<String, Object> validatePerson(Map<String, Object> body, Map<String, String> errors) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FieldSpec f : PERSON) values.put(f.name(), f.normalize(body.get(f.name()), f.name(), errors));
        if (values.get("contactNo") instanceof String c && !ContactNumber.isValid(c)) errors.putIfAbsent("contactNo", ContactNumber.MESSAGE);
        return values;
    }

    private void updatePerson(long userId, Map<String, Object> changes) {
        if (changes.isEmpty()) return;
        List<String> assignments = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (FieldSpec f : PERSON) {
            if (!changes.containsKey(f.name())) continue;
            assignments.add(f.column() + " = ?");
            params.add(changes.get(f.name()));
        }
        params.add(userId);
        try {
            jdbc.sql("UPDATE users SET " + String.join(", ", assignments) + " WHERE id = ?").params(params).update();
        } catch (DuplicateKeyException e) {
            throw new ValidationException(Map.of("employeeId", "This employee ID is already in use."));
        }
    }

    // ---- validation and writes for the faculty record ----

    /** @param keepDepartment / keepCadre a department or cadre the person already has stays acceptable even if it has since been closed */
    private Map<String, Object> validateFaculty(Map<String, Object> body, Map<String, String> errors, boolean requireCore,
                                                Long keepDepartment, Long keepCadre) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FieldSpec f : PROFILE) values.put(f.name(), f.normalize(body.get(f.name()), f.name(), errors));
        if (requireCore) {
            if (values.get("name") == null) errors.putIfAbsent("name", "Name is required.");
            if (values.get("employeeId") == null) errors.putIfAbsent("employeeId", "Employee ID is required.");
        }
        values.put("departmentId", reference("departmentId", "departments", body.get("departmentId"), "Choose a department.", keepDepartment, errors));
        values.put("cadreId", reference("cadreId", "cadres", body.get("cadreId"), "Choose a designation (cadre).", keepCadre, errors));
        return values;
    }

    private Long reference(String field, String table, Object raw, String message, Long keep, Map<String, String> errors) {
        Long id = AdminInput.id(raw);
        if (id == null || (!id.equals(keep) && jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE id = ? AND active = TRUE").param(id)
                .query(Integer.class).single() == 0)
                || (id.equals(keep) && jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE id = ?").param(id).query(Integer.class).single() == 0)) {
            errors.put(field, message);
            return null;
        }
        return id;
    }

    /** The departments a Head of the Department heads: at least one, each existing (and open, unless already theirs). */
    private List<Long> validateDepartments(Map<String, Object> body, Map<String, String> errors, List<Long> keep) {
        String field = "hodDepartmentIds";
        Object raw = body.get(field);
        List<Long> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                Long id = AdminInput.id(o);
                if (id == null || jdbc.sql("SELECT COUNT(*) FROM departments WHERE id = ?" + (keep.contains(id) ? "" : " AND active = TRUE"))
                        .param(id).query(Integer.class).single() == 0) {
                    errors.put(field, "Choose existing departments.");
                    return List.of();
                }
                if (!ids.contains(id)) ids.add(id);
            }
        }
        if (ids.isEmpty()) errors.putIfAbsent(field, "Choose at least one department for the Head of the Department.");
        return ids;
    }

    private void insertProfile(long userId, Map<String, Object> v) {
        try {
            jdbc.sql("""
                    INSERT INTO faculty_profiles (user_id, employee_id, name, contact_no, department_id, cadre_id, qualification,
                        specialization, phd_status, joining_date_institution, joining_date_designation,
                        teaching_experience_years, industry_experience_years, research_experience_years,
                        orcid, scopus_id, google_scholar_id, vidwan_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                    .params(userId, v.get("employeeId"), v.get("name"), v.get("contactNo"), v.get("departmentId"),
                            v.get("cadreId"), v.get("qualification"), v.get("specialization"),
                            v.get("phdStatus") == null ? "NOT_APPLICABLE" : v.get("phdStatus"),
                            v.get("joiningDateInstitution"), v.get("joiningDateDesignation"),
                            v.get("teachingExperienceYears"), v.get("industryExperienceYears"),
                            v.get("researchExperienceYears"), v.get("orcid"), v.get("scopusId"),
                            v.get("googleScholarId"), v.get("vidwanId"))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ValidationException(Map.of("employeeId", "This employee ID is already in use."));
        }
    }

    private void updateProfile(long userId, Map<String, Object> v) {
        try {
            int n = jdbc.sql("""
                    UPDATE faculty_profiles SET employee_id = ?, name = ?, contact_no = ?, department_id = ?, cadre_id = ?,
                        qualification = ?, specialization = ?, phd_status = ?, joining_date_institution = ?,
                        joining_date_designation = ?, teaching_experience_years = ?, industry_experience_years = ?,
                        research_experience_years = ?, orcid = ?, scopus_id = ?, google_scholar_id = ?, vidwan_id = ?
                    WHERE user_id = ?""")
                    .params(v.get("employeeId"), v.get("name"), v.get("contactNo"), v.get("departmentId"), v.get("cadreId"),
                            v.get("qualification"), v.get("specialization"),
                            v.get("phdStatus") == null ? "NOT_APPLICABLE" : v.get("phdStatus"),
                            v.get("joiningDateInstitution"), v.get("joiningDateDesignation"),
                            v.get("teachingExperienceYears"), v.get("industryExperienceYears"),
                            v.get("researchExperienceYears"), v.get("orcid"), v.get("scopusId"),
                            v.get("googleScholarId"), v.get("vidwanId"), userId)
                    .update();
            if (n == 0) insertProfile(userId, v); // a faculty account created without a record
        } catch (DuplicateKeyException e) {
            throw new ValidationException(Map.of("employeeId", "This employee ID is already in use."));
        }
    }
}
