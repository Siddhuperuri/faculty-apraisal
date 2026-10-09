package edu.svec.fams.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How full the disk that holds the stored files is, how much the application itself uses, and what it is expected to use
 * once three years of issued reports are being kept. Useful before proof uploads exist, and the figures that decide
 * whether the disk is big enough once they do.
 */
@Service
public class StorageHealthService {
    private static final Logger log = LoggerFactory.getLogger(StorageHealthService.class);

    /** A typical issued report, used until the college has issued enough of its own to measure. */
    static final long TYPICAL_REPORT_BYTES = 250_000;
    private static final int PROBLEM_PERCENT = 95;

    /**
     * @param diskTotalBytes @param diskFreeBytes the disk holding the stored files (zero if it cannot be read)
     * @param reportBytes the issued reports kept; @param databaseBytes the database's tables and indexes
     * @param expectedBytes what reports and database are expected to take after the retention period
     * @param projectedPercent how full the disk would be then, if nothing else on it grows
     */
    public record StorageHealth(String level, String headline, String path, long diskTotalBytes, long diskFreeBytes,
                                double diskUsedPercent, int warnPercent, long reportBytes, int reportCount, long averageReportBytes,
                                boolean averageMeasured, long databaseBytes, int activeFaculty, int retentionYears,
                                long expectedBytes, double projectedPercent, boolean uploadsBuilt, String note) {}

    private final JdbcClient jdbc;
    private final String storageDir;
    private final int warnPercent;
    private final int retentionYears;

    public StorageHealthService(JdbcClient jdbc, @Value("${fams.documents.storage-dir:}") String storageDir,
                                @Value("${fams.ops.storage-warn-percent:80}") int warnPercent,
                                @Value("${fams.ops.retention-years:3}") int retentionYears) {
        this.jdbc = jdbc;
        this.storageDir = storageDir == null ? "" : storageDir.strip();
        this.warnPercent = warnPercent;
        this.retentionYears = retentionYears;
    }

    @Transactional(readOnly = true)
    public StorageHealth health() {
        long total = 0;
        long free = 0;
        if (!storageDir.isEmpty()) {
            try {
                var store = Files.getFileStore(Path.of(storageDir));
                total = store.getTotalSpace();
                free = store.getUsableSpace();
            } catch (IOException | RuntimeException e) {
                log.warn("Cannot read the disk holding {}", storageDir, e);
            }
        }
        double usedPercent = total == 0 ? 0 : (total - free) * 100.0 / total;

        long reportBytes = jdbc.sql("SELECT COALESCE(SUM(size_bytes), 0) FROM appraisal_reports").query(Long.class).single();
        int reports = jdbc.sql("SELECT COUNT(*) FROM appraisal_reports").query(Integer.class).single();
        long database = jdbc.sql("SELECT COALESCE(SUM(data_length + index_length), 0) FROM information_schema.tables WHERE table_schema = DATABASE()")
                .query(Long.class).single();
        int faculty = jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'FACULTY' AND status = 'ACTIVE'").query(Integer.class).single();

        boolean measured = reports >= 5;
        long average = measured ? reportBytes / reports : TYPICAL_REPORT_BYTES;
        // One report per faculty member per year is kept for the retention period. The database is assumed to grow in step with
        // the years, which errs on the generous side.
        long expected = faculty * average * retentionYears + database * retentionYears;
        long expectedExtra = Math.max(0, expected - reportBytes - database);
        double projected = total == 0 ? 0 : ((total - free) + expectedExtra) * 100.0 / total;

        String level;
        String headline;
        if (total == 0) {
            level = "warn";
            headline = "The disk holding the stored files cannot be measured.";
        } else if (usedPercent >= PROBLEM_PERCENT) {
            level = "problem";
            headline = "The disk is almost full (" + Math.round(usedPercent) + "% used). Free space or move the data to a larger disk now.";
        } else if (usedPercent >= warnPercent) {
            level = "warn";
            headline = "The disk is " + Math.round(usedPercent) + "% full, past the " + warnPercent + "% warning level.";
        } else if (projected >= warnPercent) {
            level = "warn";
            headline = "The disk has room today, but would be about " + Math.round(projected) + "% full after " + retentionYears
                    + " years of reports. Plan for more space.";
        } else {
            level = "ok";
            headline = "There is plenty of space.";
        }

        String note = (measured ? "The average report size is measured from the " + reports + " reports issued so far. "
                : "Fewer than five reports have been issued, so a typical size of " + (TYPICAL_REPORT_BYTES / 1000) + " KB is assumed. ")
                + "Proof uploads (certificates, sanction letters) are not part of the application yet; they will need space of their own.";
        return new StorageHealth(level, headline, storageDir, total, free, usedPercent, warnPercent, reportBytes, reports, average,
                measured, database, faculty, retentionYears, expected, projected, false, note);
    }
}
