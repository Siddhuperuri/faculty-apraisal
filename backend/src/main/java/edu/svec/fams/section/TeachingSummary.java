package edu.svec.fams.section;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three figures the official form shows under "Courses handled": total teaching load, average pass %,
 * and average feedback %. These are plain arithmetic on the entered rows. The form does not say whether the
 * averages are weighted (by sections or hours); this uses the simple mean and is listed as an open question.
 */
final class TeachingSummary {
    private TeachingSummary() {}

    static Map<String, Object> of(List<Map<String, Object>> courses) {
        BigDecimal hours = BigDecimal.ZERO;
        List<BigDecimal> pass = new ArrayList<>();
        List<BigDecimal> phase1 = new ArrayList<>();
        List<BigDecimal> phase2 = new ArrayList<>();
        for (Map<String, Object> c : courses) {
            hours = hours.add((BigDecimal) c.get("hoursPerWeek"));
            pass.add((BigDecimal) c.get("passPercentage"));
            if (c.get("phase1Feedback") != null) phase1.add((BigDecimal) c.get("phase1Feedback"));
            if (c.get("phase2Feedback") != null) phase2.add((BigDecimal) c.get("phase2Feedback"));
        }
        List<BigDecimal> both = new ArrayList<>(phase1);
        both.addAll(phase2);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("courseCount", courses.size());
        m.put("totalHoursPerWeek", hours.setScale(1, RoundingMode.HALF_UP));
        m.put("averagePassPercentage", mean(pass));
        m.put("averagePhase1Feedback", mean(phase1));
        m.put("averagePhase2Feedback", mean(phase2));
        m.put("averageFeedback", mean(both));
        return m;
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }
}
