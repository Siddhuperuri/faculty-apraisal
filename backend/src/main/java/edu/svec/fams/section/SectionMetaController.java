package edu.svec.fams.section;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the section definitions so the frontend renders and pre-validates forms from the same source
 * the server validates against. The server remains authoritative; this is for usability only.
 */
@RestController
@RequestMapping("/api/sections/meta")
public class SectionMetaController {

    public record FieldMeta(String name, String label, String type, boolean required, boolean submitRequired, Integer maxLength,
                            BigDecimal min, BigDecimal max, Integer scale, List<String> allowed,
                            String dependsOn, Map<String, List<String>> allowedBy, boolean derived, boolean inAcademicYear) {}

    public record DateRangeMeta(String startField, String endField) {}

    public record SectionMeta(String key, boolean singleton, List<FieldMeta> fields,
                              List<DateRangeMeta> dateRanges, String uniqueField) {}

    @GetMapping
    public List<SectionMeta> meta() {
        return Sections.all().values().stream().map(SectionMetaController::toMeta).toList();
    }

    private static SectionMeta toMeta(SectionSpec s) {
        List<FieldMeta> fields = s.fields().stream().map(f -> new FieldMeta(
                f.name(), f.label(), f.type().name(), f.required(), f.submitRequired(),
                f.type() == FieldSpec.Type.TEXT ? f.maxLength() : null,
                f.type() == FieldSpec.Type.INT || f.type() == FieldSpec.Type.DECIMAL ? f.min() : null,
                f.type() == FieldSpec.Type.INT || f.type() == FieldSpec.Type.DECIMAL ? f.max() : null,
                f.type() == FieldSpec.Type.DECIMAL ? f.scale() : null,
                f.type() == FieldSpec.Type.ENUM ? f.allowed() : null,
                s.dependentChoice() != null && s.dependentChoice().field().equals(f.name()) ? s.dependentChoice().onField() : null,
                s.dependentChoice() != null && s.dependentChoice().field().equals(f.name()) ? s.dependentChoice().allowedBy() : null,
                s.derivedDays() != null && s.derivedDays().daysField().equals(f.name()),
                s.inAcademicYear().contains(f.name()))).toList();
        return new SectionMeta(s.key(), s.singleton(), fields,
                s.dateRanges().stream().map(r -> new DateRangeMeta(r.startField(), r.endField())).toList(),
                s.uniqueField());
    }
}
