package edu.svec.fams.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.TestDb;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** What the backup panel says in each situation the script can leave a folder in. */
@SpringBootTest
@ActiveProfiles("test")
class BackupHealthServiceTest {

    @Autowired ObjectMapper json;
    @Autowired RestoreTestService restoreTests;
    @Autowired TestDb db;

    @TempDir Path folder;
    FamsUserPrincipal admin;

    @BeforeEach
    void setUp() {
        db.reset();
        admin = db.user("admin@test.edu", Role.ADMIN);
    }

    private BackupHealthService watching(String dir) {
        return new BackupHealthService(json, restoreTests, dir, "", 36, 180);
    }

    private void backupMade(OffsetDateTime at) throws Exception {
        Files.writeString(folder.resolve(BackupHealthService.LATEST),
                "{\"finishedAt\":\"" + at + "\",\"folder\":\"fams-backup-1\",\"totalBytes\":123456,\"files\":7}", StandardCharsets.UTF_8);
    }

    private void restoreTested(int daysAgo, String result) {
        restoreTests.record(admin, Map.of("testedOn", LocalDate.now().minusDays(daysAgo).toString(), "result", result));
    }

    @Test
    void withNoFolderSetItSaysBackupsAreNotWatched() {
        var h = watching("").health();
        assertEquals("warn", h.level());
        assertFalse(h.configured());
        assertEquals("NOT_SET", h.destinationStatus());
    }

    @Test
    void anEmptyFolderMeansNoBackupHasBeenMade() {
        var h = watching(folder.toString()).health();
        assertEquals("problem", h.level());
        assertEquals("No backup has been made yet.", h.headline());
        assertNull(h.lastBackupAt());
        assertEquals("OK", h.destinationStatus());
        assertNotNull(h.destinationFreeBytes());
    }

    @Test
    void aFolderThatIsGoneIsReportedAsMissing() {
        var h = watching(folder.resolve("not-there").toString()).health();
        assertEquals("MISSING", h.destinationStatus());
        assertEquals("problem", h.level());
    }

    @Test
    void aSettingThatIsNotAPathIsReportedNotThrown() {
        var h = watching("bad\0folder").health();
        assertEquals("problem", h.level());
        assertEquals("MISSING", h.destinationStatus());
    }

    @Test
    void aRecentBackupWithATestedRestoreIsHealthy() throws Exception {
        backupMade(OffsetDateTime.now().minusHours(3));
        restoreTested(20, "PASSED");
        var h = watching(folder.toString()).health();
        assertEquals("ok", h.level());
        assertEquals("Backups are up to date.", h.headline());
        assertFalse(h.overdue());
        assertEquals(123456L, h.lastBackupBytes());
        assertEquals(7, h.lastBackupFiles());
        assertEquals("fams-backup-1", h.lastBackupFolder());
        assertTrue(h.ageHours() >= 2.9 && h.ageHours() < 3.2);
    }

    @Test
    void anOverdueBackupIsAProblemWhateverElseIsGood() throws Exception {
        backupMade(OffsetDateTime.now().minusHours(60));
        restoreTested(1, "PASSED");
        var h = watching(folder.toString()).health();
        assertEquals("problem", h.level());
        assertTrue(h.overdue());
        assertTrue(h.headline().contains("overdue"));
        assertTrue(h.headline().contains("2 days"));
    }

    @Test
    void aFailureAfterTheLastGoodBackupIsAProblem() throws Exception {
        backupMade(OffsetDateTime.now().minusHours(10));
        Files.writeString(folder.resolve(BackupHealthService.FAILURE),
                "{\"failedAt\":\"" + OffsetDateTime.now().minusHours(1) + "\",\"message\":\"mysqldump failed\"}", StandardCharsets.UTF_8);
        var h = watching(folder.toString()).health();
        assertEquals("problem", h.level());
        assertEquals("mysqldump failed", h.lastFailureMessage());
        assertNotNull(h.lastFailureAt());

        // A good backup made after the failure clears it.
        backupMade(OffsetDateTime.now());
        assertNull(watching(folder.toString()).health().lastFailureMessage());
    }

    @Test
    void aGoodBackupNobodyHasTriedToRestoreIsOnlyAWarning() throws Exception {
        backupMade(OffsetDateTime.now().minusHours(1));
        var none = watching(folder.toString()).health();
        assertEquals("warn", none.level());
        assertNull(none.lastRestoreTest());
        assertTrue(none.restoreTestOverdue());

        restoreTested(400, "PASSED");
        var old = watching(folder.toString()).health();
        assertEquals("warn", old.level());
        assertTrue(old.restoreTestOverdue());

        // A failed test does not count as a verified restore.
        restoreTested(2, "FAILED");
        assertEquals(LocalDate.now().minusDays(400), watching(folder.toString()).health().lastRestoreTest().testedOn());
    }

    @Test
    void backupsOnTheSameDiskAsTheStoredFilesAreFlagged() throws Exception {
        backupMade(OffsetDateTime.now().minusHours(1));
        restoreTested(5, "PASSED");
        Path stored = Files.createTempDirectory(folder, "stored");
        var h = new BackupHealthService(json, restoreTests, folder.toString(), stored.toString(), 36, 180).health();
        assertEquals("SAME_DISK", h.destinationStatus());
        assertEquals("warn", h.level());
    }

    @Test
    void unreadableOrOversizedStatusFilesAreIgnoredNotTrusted() throws Exception {
        Files.writeString(folder.resolve(BackupHealthService.LATEST), "this is not json", StandardCharsets.UTF_8);
        assertEquals("No backup has been made yet.", watching(folder.toString()).health().headline());
        Files.writeString(folder.resolve(BackupHealthService.LATEST), "x".repeat(70 * 1024), StandardCharsets.UTF_8);
        assertEquals("No backup has been made yet.", watching(folder.toString()).health().headline());
    }
}
