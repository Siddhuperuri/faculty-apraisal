package edu.svec.fams.dev;

import edu.svec.fams.auth.DefaultPassword;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT ONLY (profile "dev"): creates demo accounts so every console can be tried without setting accounts up by
 * hand: an administrator, the Principal, the Director Technical, the Head of the CSE department, and one faculty member of each cadre in that
 * department (so each cadre's maximum marks and scoring components can be seen). They all have the standard first
 * password ({@link DefaultPassword}). Never enable the dev profile in production. Idempotent.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final String ADMIN = "kavitha.reddy@dev.local";
    private static final String PRINCIPAL = "venkat.rao@dev.local";
    private static final String DIRECTOR = "suresh.babu@dev.local";
    private static final String HOD = "padmaja.sharma@dev.local";

    private record Faculty(String email, String employeeId, String name, String cadre) {}

    /** One per cadre, all in CSE, so each one's appraisal reaches the demo Head of the Department. */
    private static final List<Faculty> FACULTY = List.of(
            new Faculty("anil.kumar@dev.local", "DEV-101", "Mr. Anil Kumar", "LECTURER"),
            new Faculty("priya.nair@dev.local", "DEV-102", "Ms. Priya Nair", "ASST_PROF"),
            new Faculty("rahul.verma@dev.local", "DEV-103", "Dr. Rahul Verma", "SR_ASST_PROF"),
            new Faculty("lakshmi.iyer@dev.local", "DEV-104", "Dr. Lakshmi Iyer", "ASSOC_PROF"),
            new Faculty("srinivas.murthy@dev.local", "DEV-105", "Dr. Srinivas Murthy", "PROFESSOR"));

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final DefaultPassword defaultPassword;

    public DevDataSeeder(JdbcClient jdbc, PasswordEncoder encoder, DefaultPassword defaultPassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.defaultPassword = defaultPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        user(ADMIN, "ADMIN");
        user(PRINCIPAL, "PRINCIPAL");
        user(DIRECTOR, "DIRECTOR");
        long hod = user(HOD, "HOD");
        jdbc.sql("""
                INSERT IGNORE INTO hod_assignments (user_id, department_id)
                VALUES (?, (SELECT id FROM departments WHERE code = 'CSE'))""").param(hod).update();

        for (Faculty f : FACULTY) {
            long id = user(f.email(), "FACULTY");
            jdbc.sql("""
                    INSERT IGNORE INTO faculty_profiles (user_id, employee_id, name, department_id, cadre_id)
                    VALUES (?, ?, ?, (SELECT id FROM departments WHERE code = 'CSE'), (SELECT id FROM cadres WHERE code = ?))""")
                    .params(id, f.employeeId(), f.name(), f.cadre()).update();
        }
        log.info("Dev demo accounts ready: {} (administrator), {} (Principal), {} (Director Technical), {} (HoD, CSE) and {} faculty, one per cadre",
                ADMIN, PRINCIPAL, DIRECTOR, HOD, FACULTY.size());
    }

    private long user(String email, String role) {
        jdbc.sql("INSERT IGNORE INTO users (email, password_hash, role) VALUES (?,?,?)")
                .params(email, encoder.encode(defaultPassword.value()), role).update();
        return jdbc.sql("SELECT id FROM users WHERE email = ?").param(email).query(Long.class).single();
    }
}
