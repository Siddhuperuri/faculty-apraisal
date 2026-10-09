package edu.svec.fams.ops;

import edu.svec.fams.admin.AccountImportService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.common.ApiException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A record of every CSV import: when, by whom, how many accounts were created and how many rows were refused, with the list of
 * problems kept so the administrator can download it again. An import is all or nothing, so a refused file creates nothing.
 */
@Service
public class ImportHistoryService {

    /** @param administrator by name, else e-mail address; @param outcome CREATED or REJECTED */
    public record Entry(long id, OffsetDateTime importedAt, String administrator, int rowsInFile, int createdCount, int rejectedRows,
                        String outcome, boolean hasReport) {}

    private static final int KEEP = 50;
    private static final String HEADER = "Line,Column,Problem\r\n";

    private final JdbcClient jdbc;

    public ImportHistoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Notes the outcome of one import that was read and checked. */
    @Transactional
    public void record(FamsUserPrincipal actor, AccountImportService.Result result) {
        boolean refused = !result.errors().isEmpty();
        Set<Integer> lines = new HashSet<>();
        result.errors().forEach(e -> lines.add(e.row()));
        insert(actor, result.accounts(), refused ? 0 : result.created(), refused ? lines.size() : 0, refused ? "REJECTED" : "CREATED",
                refused ? report(result.errors(), result.moreErrors()) : null);
    }

    /** Notes a file that could not be read at all (empty, wrong columns, too large). */
    @Transactional
    public void recordUnreadable(FamsUserPrincipal actor, String reason) {
        insert(actor, 0, 0, 0, "REJECTED", HEADER + row("", "", reason));
    }

    @Transactional(readOnly = true)
    public List<Entry> recent() {
        return jdbc.sql("""
                SELECT h.id, h.imported_at, COALESCE(u.name, u.email) AS who, h.rows_in_file, h.created_count, h.rejected_rows, h.outcome,
                       h.error_report IS NOT NULL AS has_report
                FROM import_history h JOIN users u ON u.id = h.admin_id
                ORDER BY h.id DESC""" + " LIMIT " + KEEP)
                .query((rs, n) -> new Entry(rs.getLong("id"), rs.getObject("imported_at", OffsetDateTime.class), rs.getString("who"),
                        rs.getInt("rows_in_file"), rs.getInt("created_count"), rs.getInt("rejected_rows"), rs.getString("outcome"),
                        rs.getBoolean("has_report")))
                .list();
    }

    /** The CSV of problems for one refused import. */
    @Transactional(readOnly = true)
    public String report(long id) {
        return jdbc.sql("SELECT error_report FROM import_history WHERE id = ? AND error_report IS NOT NULL").param(id)
                .query(String.class).optional().orElseThrow(ApiException::notFound);
    }

    private void insert(FamsUserPrincipal actor, int rows, int created, int rejected, String outcome, String report) {
        jdbc.sql("INSERT INTO import_history (admin_id, rows_in_file, created_count, rejected_rows, outcome, error_report) VALUES (?,?,?,?,?,?)")
                .params(actor.id(), rows, created, rejected, outcome, report).update();
    }

    private static String report(List<AccountImportService.RowError> errors, boolean more) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (AccountImportService.RowError e : errors) csv.append(row(String.valueOf(e.row()), e.column(), e.message()));
        if (more) csv.append(row("", "", "There were more problems than this list shows. Correct these and upload the file again to see the rest."));
        return csv.toString();
    }

    private static String row(String line, String column, String problem) {
        return cell(line) + "," + cell(column) + "," + cell(problem) + "\r\n";
    }

    /** One CSV cell, quoted when needed, and neutralised so a spreadsheet never runs it as a formula. */
    static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
