package edu.svec.fams.section;

import edu.svec.fams.auth.FamsUserPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/appraisals/{id}/sections")
public class SectionController {

    public record SaveRequest(List<Map<String, Object>> records) {}

    private final SectionService service;

    public SectionController(SectionService service) { this.service = service; }

    /** Saved-record count per section (for the progress sidebar). */
    @GetMapping
    public Map<String, Integer> counts(@PathVariable long id, @AuthenticationPrincipal FamsUserPrincipal user) {
        return service.counts(id, user);
    }

    @GetMapping("/{key}")
    public SectionService.SectionView read(@PathVariable long id, @PathVariable String key,
                                           @AuthenticationPrincipal FamsUserPrincipal user) {
        return service.read(id, key, user);
    }

    /** Saves the section's complete list of records; see {@link SectionService}. */
    @PutMapping("/{key}")
    public SectionService.SectionView save(@PathVariable long id, @PathVariable String key,
                                           @AuthenticationPrincipal FamsUserPrincipal user,
                                           @RequestBody SaveRequest body) {
        return service.save(id, key, user, body.records());
    }
}
