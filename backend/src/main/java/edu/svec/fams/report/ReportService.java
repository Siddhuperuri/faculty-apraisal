package edu.svec.fams.report;

import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.appraisal.AppraisalService.AppraisalView;
import edu.svec.fams.appraisal.AppraisalService.HistoryRow;
import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.documents.DocumentStorage;
import edu.svec.fams.section.SectionService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces the printed form for an appraisal. Anyone who may open the appraisal may fetch its report (so the same
 * access rules apply). Until final approval the report is built fresh each time and stamped DRAFT. When the appraisal
 * is APPROVED the report is generated once, stored and from then on served unchanged: the official copy is immutable.
 */
@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    public record Report(String fileName, byte[] bytes, boolean official) {}

    private final AppraisalService appraisals;
    private final SectionService sections;
    private final DocumentStorage storage;
    private final AuditService audit;
    private final JdbcClient jdbc;

    public ReportService(AppraisalService appraisals, SectionService sections,
                         DocumentStorage storage, AuditService audit, JdbcClient jdbc) {
        this.appraisals = appraisals;
        this.sections = sections;
        this.storage = storage;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional
    public Report report(long appraisalId, FamsUserPrincipal user) {
        AppraisalView view = appraisals.get(appraisalId, user); // 404 unless this user may open the appraisal
        String fileName = fileName(view);
        // The reviewers' remarks are not shown to the faculty member, so their copy is printed without them.
        boolean withoutRemarks = user.role() == Role.FACULTY;

        if (!"APPROVED".equals(view.status())) {
            return new Report(fileName, build(view, user, false, withoutRemarks), false);
        }

        // The official copy always carries the remarks, whoever asks first; the faculty member is then given a copy without them.
        Report official = officialCopy(appraisalId, view, user, fileName);
        return withoutRemarks ? new Report(fileName, build(view, user, true, true), true) : official;
    }

    private Report officialCopy(long appraisalId, AppraisalView view, FamsUserPrincipal user, String fileName) {
        Optional<StoredReport> stored = find(appraisalId);
        if (stored.isPresent()) return new Report(fileName, read(stored.get()), true);

        // First request after approval: generate, keep, and serve exactly what was kept.
        byte[] bytes = build(view, user, true, false);
        try {
            DocumentStorage.Stored s = storage.store(new ByteArrayInputStream(bytes), Long.MAX_VALUE);
            try {
                jdbc.sql("INSERT INTO appraisal_reports (appraisal_id, storage_key, checksum, size_bytes) VALUES (?,?,?,?)")
                        .params(appraisalId, s.key(), s.sha256(), s.size()).update();
            } catch (DuplicateKeyException race) {
                storage.delete(s.key());        // another request stored it first: serve theirs
                // A plain read would use this transaction's older snapshot and not see their commit; a locking read does.
                return new Report(fileName, read(findLatest(appraisalId).orElseThrow()), true);
            } catch (RuntimeException e) {
                storage.delete(s.key());
                throw e;
            }
            audit.recordDetails(user.id(), "REPORT_ISSUED", "APPRAISAL", appraisalId,
                    Map.of("sha256", s.sha256(), "bytes", s.size()));
            return new Report(fileName, bytes, true);
        } catch (IOException e) {
            throw new IllegalStateException("The official report could not be stored", e);
        }
    }

    // ---- assembling the data ----

    private byte[] build(AppraisalView view, FamsUserPrincipal user, boolean approved, boolean withoutRemarks) {
        Map<String, SectionService.SectionView> data = sections.readAll(view.id(), user);

        var ids = jdbc.sql("""
                SELECT d.code AS dept, c.code AS cadre, a.academic_year_id AS year
                FROM appraisals a
                JOIN faculty_profiles fp ON fp.id = a.faculty_id
                JOIN departments d ON d.id = fp.department_id
                JOIN cadres c ON c.id = fp.cadre_id
                WHERE a.id = ?""")
                .param(view.id())
                .query((rs, n) -> new String[] {rs.getString("dept"), rs.getString("cadre"), rs.getString("year")})
                .single();

        // The view's own history may have had its comments withheld for this reader, so the record is read afresh.
        List<HistoryRow> history = appraisals.reviewHistory(view.id());
        HistoryRow hod = lastApproval(history, "HOD_APPROVE");
        HistoryRow dean = lastApproval(history, "DEAN_APPROVE");
        HistoryRow vp = lastApproval(history, "VP_APPROVE");
        HistoryRow principal = lastApproval(history, "PRINCIPAL_APPROVE");
        HistoryRow director = lastApproval(history, "DIRECTOR_APPROVE");

        FormPdfBuilder.Data input = new FormPdfBuilder.Data(
                view, ids[0], view.department(), ids[1], data,
                annexure(Long.parseLong(ids[2]), ids[1], view),
                remarks(hod, withoutRemarks), hod == null ? null : hod.at(),
                remarks(dean, withoutRemarks), dean == null ? null : dean.at(),
                remarks(vp, withoutRemarks), vp == null ? null : vp.at(),
                remarks(principal, withoutRemarks), principal == null ? null : principal.at(),
                remarks(director, withoutRemarks), director == null ? null : director.at(),
                approved, ReportLabels.statusLabel(view.status()), OffsetDateTime.now());
        return new FormPdfBuilder(input).build();
    }

    private static String remarks(HistoryRow approval, boolean withheld) {
        return approval == null || withheld ? null : approval.comment();
    }

    private static HistoryRow lastApproval(List<HistoryRow> history, String action) {
        HistoryRow found = null;
        for (HistoryRow h : history) {
            if (action.equals(h.action())) found = h;
        }
        return found;
    }

    /**
     * Annexure A: every cadre's maxima for the appraisal's year (latest active policy). The faculty member's own column
     * uses the maxima snapshotted on the appraisal, so the printed score sheet and Annexure always agree.
     */
    private List<FormPdfBuilder.Cadre> annexure(long yearId, String ownCadre, AppraisalView view) {
        Map<String, FormPdfBuilder.Cadre> cadres = new LinkedHashMap<>();
        jdbc.sql("SELECT id, code, name FROM cadres WHERE active = TRUE ORDER BY id")
                .query((rs, n) -> new String[] {rs.getString("code"), rs.getString("name")})
                .list().forEach(c -> cadres.put(c[0], new FormPdfBuilder.Cadre(c[0], c[1], new LinkedHashMap<>())));

        jdbc.sql("""
                SELECT c.code, k.criterion, k.max_marks
                FROM scoring_policies p
                JOIN cadres c ON c.id = p.cadre_id
                JOIN scoring_policy_criteria k ON k.policy_id = p.id
                WHERE p.academic_year_id = ? AND p.active = TRUE
                  AND p.version = (SELECT MAX(p2.version) FROM scoring_policies p2
                                   WHERE p2.academic_year_id = p.academic_year_id AND p2.cadre_id = p.cadre_id AND p2.active = TRUE)""")
                .param(yearId)
                .query((rs, n) -> new Object[] {rs.getString("code"), rs.getString("criterion"), rs.getInt("max_marks")})
                .list().forEach(r -> {
                    FormPdfBuilder.Cadre c = cadres.get((String) r[0]);
                    if (c != null) c.marks().put((String) r[1], (Integer) r[2]);
                });

        FormPdfBuilder.Cadre own = cadres.get(ownCadre);
        if (own != null) {
            own.marks().clear();
            view.scores().stream().filter(s -> s.maxMarks() != null).forEach(s -> own.marks().put(s.criterion(), s.maxMarks()));
        }
        return new ArrayList<>(cadres.values());
    }

    // ---- stored official copy ----

    private record StoredReport(String key, String sha256) {}

    private Optional<StoredReport> find(long appraisalId) {
        return jdbc.sql("SELECT storage_key, checksum FROM appraisal_reports WHERE appraisal_id = ?")
                .param(appraisalId).query((rs, n) -> new StoredReport(rs.getString(1), rs.getString(2))).optional();
    }

    /** Reads the latest committed row even inside an older snapshot (used after losing an insert race). */
    private Optional<StoredReport> findLatest(long appraisalId) {
        return jdbc.sql("SELECT storage_key, checksum FROM appraisal_reports WHERE appraisal_id = ? FOR SHARE")
                .param(appraisalId).query((rs, n) -> new StoredReport(rs.getString(1), rs.getString(2))).optional();
    }

    /**
     * The stored bytes, but only if they are still exactly what was issued. The database row (which cannot be changed)
     * holds the SHA-256 taken when the report was stored; a file altered or replaced on disk since then is never served.
     */
    private byte[] read(StoredReport r) {
        byte[] bytes;
        try (InputStream in = storage.open(r.key())) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            log.error("The stored official report {} is missing", r.key(), e);
            throw new IllegalStateException("The official report is not available", e);
        }
        String actual;
        try {
            actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always present
        }
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), r.sha256().getBytes(StandardCharsets.US_ASCII))) {
            log.error("The stored official report {} does not match its recorded checksum: it has been altered on disk and was not served", r.key());
            throw new IllegalStateException("The official report failed its integrity check");
        }
        return bytes;
    }

    private static String fileName(AppraisalView view) {
        String id = view.employeeId().replaceAll("[^A-Za-z0-9._-]", "_");
        return "Appraisal-" + view.academicYear() + "-" + id + ".pdf";
    }
}
