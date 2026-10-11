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
                            String dependsOn, Map<String, List<String>> allowedBy, boolean derived, boolean inAcademicYear,
                            String onlyWhenField, String onlyWhenEquals,
                            Map<String, String> labels, Map<String, List<String>> suggestions) {}

    public record DateRangeMeta(String startField, String endField) {}

    public record SectionMeta(String key, boolean singleton, List<FieldMeta> fields,
                              List<DateRangeMeta> dateRanges, String uniqueField) {}

    private final ChoiceSource choices;

    public SectionMetaController(ChoiceSource choices) { this.choices = choices; }

    @GetMapping
    public List<SectionMeta> meta() {
        return Sections.all().values().stream().map(this::toMeta).toList();
    }

    private static SectionSpec.ConditionalField conditional(SectionSpec s, String field) {
        return s.conditionalFields().stream().filter(c -> c.field().equals(field)).findFirst().orElse(null);
    }

    private SectionMeta toMeta(SectionSpec s) {
        List<FieldMeta> fields = s.fields().stream().map(f -> field(s, f)).toList();
        return new SectionMeta(s.key(), s.singleton(), fields,
                s.dateRanges().stream().map(r -> new DateRangeMeta(r.startField(), r.endField())).toList(),
                s.uniqueField());
    }

    private FieldMeta field(SectionSpec s, FieldSpec f) {
        boolean numeric = f.type() == FieldSpec.Type.INT || f.type() == FieldSpec.Type.DECIMAL;
        String list = f.listCode();
        List<String> allowed = list != null ? choices.active(list) : f.allowed();
        BigDecimal min = numeric ? f.min() : null;
        BigDecimal max = numeric ? f.max() : null;
        if (numeric && list != null && !allowed.isEmpty()) {   // the list, not a fixed range, sets the bounds shown
            min = allowed.stream().map(BigDecimal::new).min(BigDecimal::compareTo).orElse(min);
            max = allowed.stream().map(BigDecimal::new).max(BigDecimal::compareTo).orElse(max);
        }
        SectionSpec.DependentChoice dep = s.dependentChoice();
        boolean dependent = dep != null && dep.field().equals(f.name());
        SectionSpec.ConditionalField cond = conditional(s, f.name());
        Map<String, List<String>> suggestions = null;
        for (SectionSpec.Suggestions sg : s.suggestions()) {
            if (!sg.field().equals(f.name())) continue;
            suggestions = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, String> e : sg.listByValue().entrySet()) suggestions.put(e.getKey(), choices.active(e.getValue()));
        }
        return new FieldMeta(f.name(), f.label(), f.type().name(), f.required(), f.submitRequired(),
                f.type() == FieldSpec.Type.TEXT ? f.maxLength() : null,
                min, max,
                f.type() == FieldSpec.Type.DECIMAL ? f.scale() : null,
                allowed.isEmpty() ? null : allowed,
                dependent ? dep.onField() : null,
                dependent ? choices.activeByParent(dep.list()) : null,
                s.derivedDays() != null && s.derivedDays().daysField().equals(f.name()),
                s.inAcademicYear().contains(f.name()),
                cond == null ? null : cond.onField(),
                cond == null ? null : cond.equals(),
                list == null ? null : choices.labels(list),
                suggestions);
    }
}
