package edu.svec.fams.ecap;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Search of ECAP's courses for the course picker on the form. The ECAP address and key stay on the server. */
@RestController
@RequestMapping("/api/ecap")
public class EcapController {

    private static final int MIN_QUERY = 2;
    private static final int LIMIT = 25;

    private final EcapClient ecap;

    public EcapController(EcapClient ecap) { this.ecap = ecap; }

    /** At least two characters of a course code or name; a 503 with a plain message when ECAP is unavailable. */
    @GetMapping("/courses")
    public Map<String, List<EcapCourse>> courses(@RequestParam(defaultValue = "") String q) {
        String text = q.strip();
        if (text.length() < MIN_QUERY || text.length() > 80) return Map.of("courses", List.of());
        return Map.of("courses", ecap.search(text, LIMIT));
    }
}
