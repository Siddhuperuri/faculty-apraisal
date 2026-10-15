package edu.svec.fams.report;

import edu.svec.fams.appraisal.AppraisalAccess;
import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.ValidationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Many appraisals' printed forms in one zip file, for the Head of the Department, the Principal, the Director Technical and
 * the administrator: the ones they selected, or every one they may open for the year. Each form is produced by
 * {@link ReportService}, so every rule about who may read an appraisal, and which reviewers' remarks are printed for whom,
 * applies unchanged; an appraisal the caller may not open is skipped (and counted), never included. A draft is never
 * included. Each download is audited.
 */
@Service
public class PdfBundleService {

    /** The most forms in one zip: the page asks for a smaller selection (a department at a time) beyond it. */
    public static final int MAX_FORMS = 300;

    /** @param bytes the zip; @param included forms in it; @param skipped selected appraisals that were left out (not visible, or a draft) */
    public record Bundle(String fileName, byte[] bytes, int included, int skipped) {}

    private final ReportService reports;
    private final AppraisalService appraisals;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public PdfBundleService(ReportService reports, AppraisalService appraisals, JdbcClient jdbc, AuditService audit) {
        this.reports = reports;
        this.appraisals = appraisals;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * Body: {@code {"appraisalIds": [..]}} or {@code {"facultyIds": [..], "academicYearId": n}} for a selection, or
     * {@code {"all": true, "academicYearId": n}} for everything the caller may open that year.
     */
    public Bundle bundle(FamsUserPrincipal user, Map<String, Object> body) {
        if (user.role() == Role.FACULTY) throw ApiException.forbidden("Your role cannot download appraisals in bulk.");
        Long year = body.get("academicYearId") == null ? null
                : ExportRequest.ids(Map.of("academicYearId", List.of(body.get("academicYearId"))), "academicYearId", 1).getFirst();
        boolean all = Boolean.TRUE.equals(body.get("all"));

        List<Long> ids;
        String mode;
        if (all) {
            mode = "ALL";
            ids = everything(user, year);
        } else if (body.get("appraisalIds") != null) {
            mode = "APPRAISALS";
            ids = ExportRequest.ids(body, "appraisalIds", MAX_FORMS);
            if (year != null) ids = inYear(ids, year);
        } else if (body.get("facultyIds") != null) {
            mode = "FACULTY";
            ids = ofFaculty(ExportRequest.ids(body, "facultyIds", ExportRequest.MAX_IDS), year);
        } else {
            throw new ValidationException(Map.of("appraisalIds", "Select at least one appraisal, or choose to download all."));
        }
        if (ids.size() > MAX_FORMS) {
            throw ApiException.conflict("That is " + ids.size() + " appraisals. A zip holds at most " + MAX_FORMS
                    + ": download one department or one group at a time.");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Set<String> names = new HashSet<>();
        int included = 0;
        int skipped = 0;
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Long id : ids) {
                ReportService.Report r;
                try {
                    r = produce(user, id);
                } catch (ApiException notVisible) {
                    skipped++;
                    continue;
                }
                zip.putNextEntry(new ZipEntry(unique(names, r.fileName())));
                zip.write(r.bytes());
                zip.closeEntry();
                included++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (included == 0) {
            throw ApiException.conflict("None of these appraisals can be downloaded: a draft is private, and an appraisal "
                    + "appears here only once it has been submitted.");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", mode);
        details.put("included", included);
        details.put("skipped", skipped);
        details.put("academicYearId", year);
        audit.recordDetails(user.id(), "EXPORT_APPRAISAL_PDFS", "APPRAISAL_EXPORT", null, details);
        String name = "appraisal-pdfs-" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")) + ".zip";
        return new Bundle(name, out.toByteArray(), included, skipped);
    }

    private ReportService.Report produce(FamsUserPrincipal user, long id) {
        if (user.role() == Role.ADMIN) return AppraisalAccess.asAdminExport(() -> reports.report(id, user));
        return reports.report(id, user);
    }

    /** Every appraisal the caller may open (an administrator: every submitted one), for the year given or the open one. */
    private List<Long> everything(FamsUserPrincipal user, Long year) {
        long yearId = year != null ? year : openYear();
        if (user.role() == Role.ADMIN) {
            return jdbc.sql("""
                    SELECT a.id FROM appraisals a
                    JOIN faculty_profiles fp ON fp.id = a.faculty_id
                    JOIN users u ON u.id = fp.user_id AND u.deleted_at IS NULL
                    WHERE a.academic_year_id = ? AND a.status <> 'DRAFT' ORDER BY fp.name, a.id""")
                    .param(yearId).query(Long.class).list();
        }
        List<Long> visible = appraisals.list(user).stream().map(AppraisalService.ListItem::id).toList();
        return visible.isEmpty() ? visible : inYear(visible, yearId);
    }

    private List<Long> inYear(List<Long> ids, long year) {
        return jdbc.sql("SELECT id FROM appraisals WHERE id IN (:ids) AND academic_year_id = :year")
                .param("ids", ids).param("year", year).query(Long.class).list().stream()
                .sorted(java.util.Comparator.comparingInt(ids::indexOf)).toList();
    }

    /** The appraisals of the chosen faculty for the year (the open one when none is given); a faculty member without one has none. */
    private List<Long> ofFaculty(List<Long> facultyIds, Long year) {
        long yearId = year != null ? year : openYear();
        return jdbc.sql("""
                SELECT a.id FROM appraisals a WHERE a.faculty_id IN (:fids) AND a.academic_year_id = :year AND a.status <> 'DRAFT'
                ORDER BY a.id""")
                .param("fids", facultyIds).param("year", yearId).query(Long.class).list();
    }

    private long openYear() {
        return jdbc.sql("SELECT id FROM academic_years ORDER BY active DESC, start_date DESC, id DESC LIMIT 1")
                .query(Long.class).optional().orElseThrow(() -> ApiException.conflict("No academic year is configured."));
    }

    private static String unique(Set<String> names, String wanted) {
        String name = wanted;
        for (int n = 2; !names.add(name); n++) {
            int dot = wanted.lastIndexOf('.');
            name = dot < 0 ? wanted + "-" + n : wanted.substring(0, dot) + "-" + n + wanted.substring(dot);
        }
        return name;
    }
}
