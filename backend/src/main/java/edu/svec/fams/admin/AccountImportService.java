package edu.svec.fams.admin;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.ContactNumber;
import edu.svec.fams.auth.DefaultPassword;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.ValidationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Creates many accounts from one CSV file. Every row is checked first, with the same rules as adding an account by hand,
 * and the accounts are created only if every row is right: a file that is part right creates nothing, and the
 * administrator gets the list of rows to correct and uploads the file again. Everyone starts with the standard first
 * password and must replace it at first sign-in.
 */
@Service
public class AccountImportService {

    public static final int MAX_ACCOUNTS = 1000;
    private static final int MAX_ERRORS = 200;

    /** One thing wrong in one row. {@code row} is the line of the file (the header is line 1); {@code column} names the column. */
    public record RowError(int row, String column, String message) {}

    /**
     * @param accounts how many accounts the file describes
     * @param created how many were created: all of them, or none when {@code errors} is not empty
     * @param byRole created accounts by role
     * @param moreErrors the list was cut short; correct these and upload again to see the rest
     */
    public record Result(int accounts, int created, Map<String, Integer> byRole, List<RowError> errors, boolean moreErrors) {}

    /** The columns, in the order of the template, with the other headings that mean the same. */
    private enum Column {
        NAME("Name", "name", "fullname", "facultyname", "employeename", "staffname"),
        EMAIL("College e-mail address", "collegeemailaddress", "collegeemail", "emailaddress", "email", "collegemailid",
                "collegeemailid", "emailid", "mailid", "mail"),
        ROLE("Role", "role", "usertype", "accounttype"),
        EMPLOYEE_ID("Employee ID", "employeeid", "empid", "employeeno", "employeenumber", "empno", "staffid", "facultyid"),
        CONTACT("Contact number", "contactnumber", "contactno", "contact", "phone", "phonenumber", "phoneno", "mobile",
                "mobilenumber", "mobileno"),
        DEPARTMENT("Department", "department", "departments", "dept", "depts"),
        DESIGNATION("Designation (cadre)", "designationcadre", "designation", "cadre");

        final String label;
        final Set<String> names;

        Column(String label, String... names) {
            this.label = label;
            this.names = Set.of(names);
        }
    }

    private static final Pattern LIST_SEPARATOR = Pattern.compile("\\s*[;|,]\\s*");
    private static final Pattern SLASH = Pattern.compile("\\s*/\\s*");

    private final JdbcClient jdbc;
    private final AdminUserService users;
    private final PasswordEncoder encoder;
    private final DefaultPassword defaultPassword;
    private final AuditService audit;

    public AccountImportService(JdbcClient jdbc, AdminUserService users, PasswordEncoder encoder, DefaultPassword defaultPassword,
                                AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.encoder = encoder;
        this.defaultPassword = defaultPassword;
        this.audit = audit;
    }

    @Transactional
    public Result importAccounts(FamsUserPrincipal actor, byte[] file) {
        List<CsvReader.Row> rows;
        try {
            rows = CsvReader.parse(CsvReader.decode(file));
        } catch (CsvReader.CsvException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (rows.isEmpty()) throw ApiException.badRequest("The file is empty.");
        Map<Column, Integer> at = columns(rows.get(0).cells());
        List<CsvReader.Row> data = rows.subList(1, rows.size());
        if (data.isEmpty()) throw ApiException.badRequest("The file has a heading row but no accounts under it.");
        if (data.size() > MAX_ACCOUNTS) {
            throw ApiException.badRequest("The file has " + data.size() + " accounts. Upload at most " + MAX_ACCOUNTS
                    + " at a time: split the file in two.");
        }

        Index departments = departments();
        Index cadres = cadres();

        // Pass 1: check every row. Nothing is written.
        List<RowError> errors = new ArrayList<>();
        List<Map<String, Object>> bodies = new ArrayList<>();
        Set<String> emails = new HashSet<>(), facultyIds = new HashSet<>(), otherIds = new HashSet<>();
        for (CsvReader.Row row : data) {
            Map<Column, String> problems = new LinkedHashMap<>();
            Map<String, Object> body = body(row, at, departments, cadres, problems);
            Role role = Role.find(String.valueOf(body.get("role"))).orElse(null);

            String email = text(body.get("email")).toLowerCase(Locale.ROOT);
            if (!email.isEmpty() && !emails.add(email)) problems.putIfAbsent(Column.EMAIL, "This e-mail address appears more than once in the file.");
            String employeeId = text(body.get("employeeId")).toUpperCase(Locale.ROOT);
            if (!employeeId.isEmpty() && !(role == Role.FACULTY ? facultyIds : otherIds).add(employeeId)) {
                problems.putIfAbsent(Column.EMPLOYEE_ID, "This employee ID appears more than once in the file.");
            }

            try {
                users.validate(body);
            } catch (ValidationException e) {
                e.fieldErrors().forEach((field, message) -> problems.putIfAbsent(columnOf(field), message));
            }
            problems.forEach((column, message) -> errors.add(new RowError(row.line(), column.label, message)));
            bodies.add(body);
        }
        if (!errors.isEmpty()) {
            boolean more = errors.size() > MAX_ERRORS;
            return new Result(data.size(), 0, Map.of(), more ? errors.subList(0, MAX_ERRORS) : errors, more);
        }

        // Pass 2: create them all, in this one transaction. The password is hashed once for the whole file: hashing is
        // slow on purpose, and every account here starts with the same password.
        String hash = encoder.encode(defaultPassword.value());
        Map<String, Integer> byRole = new LinkedHashMap<>();
        for (int i = 0; i < bodies.size(); i++) {
            Map<String, Object> body = bodies.get(i);
            try {
                users.createWithHash(actor, body, hash);
            } catch (ValidationException e) {
                // Someone else created an account between the check and now. Nothing from this file is kept.
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                int line = data.get(i).line();
                List<RowError> raced = new ArrayList<>();
                e.fieldErrors().forEach((field, message) -> raced.add(new RowError(line, columnOf(field).label, message)));
                return new Result(data.size(), 0, Map.of(), raced, false);
            }
            byRole.merge((String) body.get("role"), 1, Integer::sum);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accounts", data.size());
        details.putAll(byRole);
        audit.recordDetails(actor.id(), "USERS_IMPORTED", "USER", null, details);
        return new Result(data.size(), data.size(), byRole, List.of(), false);
    }

    // ---- reading the heading row ----

    private static Map<Column, Integer> columns(List<String> heading) {
        Map<Column, Integer> at = new LinkedHashMap<>();
        for (int i = 0; i < heading.size(); i++) {
            String key = plain(heading.get(i));
            for (Column c : Column.values()) {
                if (!c.names.contains(key)) continue;
                if (at.putIfAbsent(c, i) != null) {
                    throw ApiException.badRequest("The column \"" + c.label + "\" appears twice in the heading row.");
                }
            }
        }
        List<String> missing = Arrays.stream(Column.values()).filter(c -> !at.containsKey(c)).map(c -> c.label).toList();
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("The first row of the file must name these columns: " + String.join(", ",
                    Arrays.stream(Column.values()).map(c -> c.label).toList()) + ". Missing: " + String.join(", ", missing) + ".");
        }
        return at;
    }

    // ---- one row ----

    /** The request body for one row, as adding an account by hand would send it. Problems found on the way go in {@code problems}. */
    private Map<String, Object> body(CsvReader.Row row, Map<Column, Integer> at, Index departments, Index cadres,
                                     Map<Column, String> problems) {
        Map<Column, String> v = new HashMap<>();
        for (Column c : Column.values()) v.put(c, cell(row.cells(), at.get(c)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", v.get(Column.EMAIL));
        body.put("name", orNull(v.get(Column.NAME)));
        if (v.get(Column.NAME).isEmpty()) problems.put(Column.NAME, "Name is required.");

        Role role = role(v.get(Column.ROLE));
        if (role == null) {
            problems.put(Column.ROLE, v.get(Column.ROLE).isEmpty()
                    ? "Role is required."
                    : "Role must be Faculty, HoD (Head of the Department), Principal, Director Technical or Administrator.");
        } else {
            body.put("role", role.name());
        }
        if (v.get(Column.EMAIL).isEmpty()) problems.put(Column.EMAIL, "E-mail address is required.");

        body.put("employeeId", orNull(v.get(Column.EMPLOYEE_ID)));
        if (role == Role.FACULTY && v.get(Column.EMPLOYEE_ID).isEmpty()) problems.put(Column.EMPLOYEE_ID, "Employee ID is required for faculty.");

        String contact = v.get(Column.CONTACT);
        if (shortenedBySpreadsheet(contact)) {
            problems.put(Column.CONTACT, "The spreadsheet has shortened this number (" + contact + "). Format the column as Text, type the number again and save.");
        } else if (!contact.isEmpty() && !ContactNumber.isValid(contact)) {
            problems.put(Column.CONTACT, ContactNumber.MESSAGE);
        }
        body.put("contactNo", problems.containsKey(Column.CONTACT) ? null : orNull(contact));

        if (role == Role.FACULTY) {
            Set<Long> dept = departments.find(v.get(Column.DEPARTMENT));
            if (v.get(Column.DEPARTMENT).isEmpty()) {
                problems.put(Column.DEPARTMENT, "Department is required for faculty.");
            } else if (dept.size() == 1) {
                body.put("departmentId", dept.iterator().next());
            } else {
                problems.put(Column.DEPARTMENT, departments.describe(v.get(Column.DEPARTMENT), dept.size() > 1, "department", "a faculty member belongs to one department"));
            }
            Set<Long> cadre = cadres.find(v.get(Column.DESIGNATION));
            if (v.get(Column.DESIGNATION).isEmpty()) {
                problems.put(Column.DESIGNATION, "Designation is required for faculty.");
            } else if (cadre.size() == 1) {
                body.put("cadreId", cadre.iterator().next());
            } else {
                problems.put(Column.DESIGNATION, cadres.describe(v.get(Column.DESIGNATION), cadre.size() > 1, "designation", null));
            }
        } else if (role == Role.HOD) {
            headedDepartments(v.get(Column.DEPARTMENT), departments, body, problems);
        }
        return body;
    }

    /** A Head of the Department's departments: one cell, one or more departments separated by ; | or a comma. */
    private static void headedDepartments(String cell, Index departments, Map<String, Object> body, Map<Column, String> problems) {
        if (cell.isEmpty()) {
            problems.put(Column.DEPARTMENT, "Department is required for a Head of the Department.");
            return;
        }
        List<String> parts = departments.find(cell).size() == 1 ? List.of(cell) : Arrays.asList(LIST_SEPARATOR.split(cell));
        List<Long> ids = new ArrayList<>();
        for (String part : parts) {
            Set<Long> found = departments.find(part);
            if (found.size() != 1) {
                problems.put(Column.DEPARTMENT, departments.describe(part, found.size() > 1, "department", null));
                return;
            }
            Long id = found.iterator().next();
            if (!ids.contains(id)) ids.add(id);
        }
        body.put("hodDepartmentIds", ids);
    }

    /** "9.88E+09": a long number that a spreadsheet has turned into scientific notation, so the digits are gone. */
    private static boolean shortenedBySpreadsheet(String s) {
        int e = Math.max(s.indexOf('e'), s.indexOf('E'));
        if (e <= 0) return false;
        String mantissa = s.substring(0, e);
        String exponent = s.substring(e + 1);
        if (exponent.startsWith("+") || exponent.startsWith("-")) exponent = exponent.substring(1);
        int dot = mantissa.indexOf('.');
        String digits = dot < 0 ? mantissa : mantissa.substring(0, dot) + mantissa.substring(dot + 1);
        return dot == mantissa.lastIndexOf('.') && allDigits(digits) && allDigits(exponent);
    }

    private static boolean allDigits(String s) {
        return !s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static Role role(String text) {
        String k = plain(text);
        if (k.equals("faculty") || k.equals("facultymember") || k.equals("teacher")) return Role.FACULTY;
        if (k.equals("hod") || k.startsWith("headof")) return Role.HOD;
        if (k.equals("principal")) return Role.PRINCIPAL;
        if (k.equals("directortechnical") || k.equals("technicaldirector") || k.equals("director")) return Role.DIRECTOR;
        if (k.equals("admin") || k.equals("administrator")) return Role.ADMIN;
        return null;
    }

    private static Column columnOf(String field) {
        return switch (field) {
            case "email" -> Column.EMAIL;
            case "role" -> Column.ROLE;
            case "name" -> Column.NAME;
            case "employeeId" -> Column.EMPLOYEE_ID;
            case "contactNo" -> Column.CONTACT;
            case "departmentId", "hodDepartmentIds" -> Column.DEPARTMENT;
            case "cadreId" -> Column.DESIGNATION;
            default -> Column.NAME;
        };
    }

    // ---- cells and names ----

    private static String cell(List<String> cells, int index) {
        return index < cells.size() ? clean(cells.get(index)) : "";
    }

    /** Trimmed, with the no-break spaces and invisible characters spreadsheets and e-mail leave behind removed. */
    private static String clean(String raw) {
        return raw.replace(' ', ' ').replaceAll("[\\u200B\\uFEFF]", "").strip();
    }

    private static String orNull(String s) {
        return s.isEmpty() ? null : s;
    }

    private static String text(Object o) {
        return o instanceof String s ? s.strip() : "";
    }

    /** Lower case letters and digits only, with "&" read as "and": the key two spellings of a name share. */
    static String plain(String s) {
        return s.toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static final Map<String, String> ABBREVIATIONS = Map.of(
            "asst", "assistant", "assist", "assistant", "assoc", "associate", "sr", "senior", "snr", "senior", "prof", "professor");

    /** "Sr. Asst. Prof." and "Senior Assistant Professor" give the same key. */
    static String cadreKey(String s) {
        StringBuilder key = new StringBuilder();
        for (String word : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            key.append(ABBREVIATIONS.getOrDefault(word, word));
        }
        return key.toString();
    }

    // ---- departments and designations by name ----

    /** The open departments, by their code, their full name, and each half of a name like "Computer Science / Information Technology". */
    private Index departments() {
        Index index = new Index(AccountImportService::plain);
        for (Map<String, Object> d : jdbc.sql("SELECT id, code, name FROM departments WHERE active = TRUE ORDER BY code").query().listOfRows()) {
            long id = ((Number) d.get("id")).longValue();
            String name = (String) d.get("name");
            index.add(id, (String) d.get("code"));
            index.add(id, name);
            for (String part : SLASH.split(name)) index.add(id, part);
            index.shown.add((String) d.get("code"));
        }
        return index;
    }

    /** The open designations, by their code or name, "Asst. Prof." and "Assistant Professor" being the same. */
    private Index cadres() {
        Index index = new Index(AccountImportService::cadreKey);
        for (Map<String, Object> c : jdbc.sql("SELECT id, code, name FROM cadres WHERE active = TRUE ORDER BY id").query().listOfRows()) {
            long id = ((Number) c.get("id")).longValue();
            index.add(id, (String) c.get("code"));
            index.add(id, (String) c.get("name"));
            index.shown.add((String) c.get("name"));
        }
        return index;
    }

    private static final class Index {
        private final UnaryOperator<String> key;
        private final Map<String, Set<Long>> byKey = new HashMap<>();
        final List<String> shown = new ArrayList<>();

        Index(UnaryOperator<String> key) { this.key = key; }

        void add(long id, String name) {
            String k = key.apply(name);
            if (!k.isEmpty()) byKey.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(id);
        }

        Set<Long> find(String text) {
            return byKey.getOrDefault(key.apply(text), Set.of());
        }

        String describe(String text, boolean ambiguous, String what, String hint) {
            if (ambiguous) return "\"" + text + "\" could be more than one " + what + ". Write its code: " + String.join(", ", shown) + ".";
            if (hint != null && LIST_SEPARATOR.split(text).length > 1) return "Give one " + what + ": " + hint + ".";
            return "\"" + text + "\" is not a " + what + " in the system. Use one of: " + String.join(", ", shown) + ".";
        }
    }
}
