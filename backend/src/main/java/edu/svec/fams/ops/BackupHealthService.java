package edu.svec.fams.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.ops.RestoreTestService.RestoreTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Whether the college's data is being backed up, and whether a backup has ever been proved to restore. The application
 * does not take backups itself: {@code scripts/backup.ps1} does, and leaves {@code latest.json} (after a good backup) or
 * {@code last-failure.json} (after a failed one) in the folder it was given. This reads those two files and the folder.
 */
@Service
public class BackupHealthService {
    private static final Logger log = LoggerFactory.getLogger(BackupHealthService.class);

    public static final String LATEST = "latest.json";
    public static final String FAILURE = "last-failure.json";

    /**
     * @param level ok, warn or problem, for the colour of the panel; @param headline the one line to read first
     * @param destinationStatus OK, NOT_SET, MISSING, NOT_WRITABLE or SAME_DISK
     * @param ageHours hours since the last good backup; null if there has never been one
     */
    public record BackupHealth(String level, String headline, boolean configured, String destination, String destinationStatus,
                               String destinationNote, Long destinationFreeBytes, OffsetDateTime lastBackupAt, Double ageHours,
                               boolean overdue, int maxAgeHours, Long lastBackupBytes, Integer lastBackupFiles, String lastBackupFolder,
                               OffsetDateTime lastFailureAt, String lastFailureMessage, RestoreTest lastRestoreTest,
                               boolean restoreTestOverdue, int restoreTestMaxAgeDays) {}

    private final ObjectMapper json;
    private final RestoreTestService restoreTests;
    private final String backupDir;
    private final String storageDir;
    private final int maxAgeHours;
    private final int restoreMaxAgeDays;

    public BackupHealthService(ObjectMapper json, RestoreTestService restoreTests,
                               @Value("${fams.ops.backup-dir:}") String backupDir,
                               @Value("${fams.documents.storage-dir:}") String storageDir,
                               @Value("${fams.ops.backup-max-age-hours:36}") int maxAgeHours,
                               @Value("${fams.ops.restore-test-max-age-days:180}") int restoreMaxAgeDays) {
        this.json = json;
        this.restoreTests = restoreTests;
        this.backupDir = backupDir == null ? "" : backupDir.strip();
        this.storageDir = storageDir == null ? "" : storageDir.strip();
        this.maxAgeHours = maxAgeHours;
        this.restoreMaxAgeDays = restoreMaxAgeDays;
    }

    /** The folder the backups are watched in, or empty when none is set. */
    public String destination() { return backupDir; }

    public BackupHealth health() {
        RestoreTest passed = restoreTests.lastPassed();
        boolean restoreOverdue = passed == null || passed.testedOn().isBefore(LocalDate.now().minusDays(restoreMaxAgeDays));

        if (backupDir.isEmpty()) {
            return new BackupHealth("warn", "Backups are not being watched. Set FAMS_BACKUP_DIR to the folder the backup script writes to.",
                    false, "", "NOT_SET", "No backup folder is configured, so the console cannot tell whether backups are happening.",
                    null, null, null, false, maxAgeHours, null, null, null, null, null, passed, restoreOverdue, restoreMaxAgeDays);
        }

        Path dir;
        try {
            dir = Path.of(backupDir);
        } catch (InvalidPathException e) {
            return new BackupHealth("problem", "FAMS_BACKUP_DIR is not a valid folder name.", true, backupDir, "MISSING",
                    "The backup folder setting is not a valid path. Check FAMS_BACKUP_DIR.", null, null, null, false, maxAgeHours, null, null,
                    null, null, null, passed, restoreOverdue, restoreMaxAgeDays);
        }
        String status = "OK";
        String note = "The backup folder is reachable.";
        Long free = null;
        if (!Files.isDirectory(dir)) {
            status = "MISSING";
            note = "The backup folder cannot be found. If it is a drive or network share, it may be disconnected.";
        } else {
            try {
                free = Files.getFileStore(dir).getUsableSpace();
            } catch (IOException e) {
                log.warn("Cannot read free space of the backup folder {}", dir, e);
            }
            if (!Files.isWritable(dir)) {
                status = "NOT_WRITABLE";
                note = "The backup folder is there but cannot be written to, so new backups will fail.";
            } else if (sameDisk(dir)) {
                status = "SAME_DISK";
                note = "The backups are on the same disk as the stored files. If that disk fails, both are lost: use another disk, "
                        + "another machine or an external drive.";
            }
        }

        JsonNode latest = read(dir.resolve(LATEST));
        JsonNode failure = read(dir.resolve(FAILURE));
        OffsetDateTime lastAt = time(latest, "finishedAt");
        OffsetDateTime failedAt = time(failure, "failedAt");
        Double age = lastAt == null ? null : Duration.between(lastAt, OffsetDateTime.now()).toMinutes() / 60.0;
        boolean overdue = lastAt == null || age > maxAgeHours;
        boolean failedSince = failedAt != null && (lastAt == null || failedAt.isAfter(lastAt));

        String level;
        String headline;
        if (lastAt == null) {
            level = "problem";
            headline = "No backup has been made yet.";
        } else if (overdue) {
            level = "problem";
            headline = "The last backup is overdue: it was made " + ago(age) + " ago, and one is expected at least every "
                    + maxAgeHours + " hours.";
        } else if (failedSince) {
            level = "problem";
            headline = "The most recent backup attempt failed.";
        } else if (!status.equals("OK")) {
            level = status.equals("SAME_DISK") ? "warn" : "problem";
            headline = note;
        } else if (passed == null) {
            level = "warn";
            headline = "Backups are being made, but no restore has been tested yet.";
        } else if (restoreOverdue) {
            level = "warn";
            headline = "Backups are being made, but the last restore test was more than " + restoreMaxAgeDays + " days ago.";
        } else {
            level = "ok";
            headline = "Backups are up to date.";
        }

        return new BackupHealth(level, headline, true, backupDir, status, note, free, lastAt, age, overdue, maxAgeHours,
                number(latest, "totalBytes"), latest == null || !latest.hasNonNull("files") ? null : latest.get("files").asInt(),
                latest == null ? null : text(latest, "folder"), failedSince ? failedAt : null,
                failedSince ? text(failure, "message") : null, passed, restoreOverdue, restoreMaxAgeDays);
    }

    private boolean sameDisk(Path backup) {
        if (storageDir.isEmpty()) return false;
        try {
            return Files.getFileStore(backup).equals(Files.getFileStore(Path.of(storageDir)));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private JsonNode read(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) < 64 * 1024 ? json.readTree(Files.readAllBytes(file)) : null;
        } catch (IOException e) {
            log.warn("Cannot read {}", file, e);
            return null;
        }
    }

    private static OffsetDateTime time(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Long number(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) && node.get(field).canConvertToLong() ? node.get(field).asLong() : null;
    }

    private static String ago(double hours) {
        long h = Math.round(hours);
        return h < 48 ? h + " hour" + (h == 1 ? "" : "s") : Duration.of(h, ChronoUnit.HOURS).toDays() + " days";
    }
}
