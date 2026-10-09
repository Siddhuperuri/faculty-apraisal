package edu.svec.fams.scoring;

import edu.svec.fams.auth.FamsUserPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/appraisals/{id}/scores")
public class ScoreController {

    /** Criterion code to self-score (a number, or null to clear). Omitted criteria are left as they are. */
    public record SaveScoresRequest(Map<String, Object> scores) {}

    private final ScoreService service;

    public ScoreController(ScoreService service) { this.service = service; }

    @PutMapping
    public List<ScoreService.ScoreRow> save(@PathVariable long id, @AuthenticationPrincipal FamsUserPrincipal user,
                                            @RequestBody SaveScoresRequest body) {
        return service.save(id, user, body.scores());
    }
}
