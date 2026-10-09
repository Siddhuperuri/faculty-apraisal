package edu.svec.fams.appraisal;

import edu.svec.fams.common.ValidationException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import edu.svec.fams.auth.FamsUserPrincipal;

@RestController
@RequestMapping("/api/appraisals")
public class AppraisalController {

    public record CommentRequest(String comment) {}

    /** The form's Declaration, accepted explicitly. */
    public record SubmitRequest(boolean declarationAccepted) {}

    /** A message from the Head of the Department to the faculty member. */
    public record MessageRequest(String message) {}

    private final AppraisalService service;
    private final MessageService messages;

    public AppraisalController(AppraisalService service, MessageService messages) {
        this.service = service;
        this.messages = messages;
    }

    @GetMapping
    public List<AppraisalService.ListItem> list(@AuthenticationPrincipal FamsUserPrincipal user) {
        return service.list(user);
    }

    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@AuthenticationPrincipal FamsUserPrincipal user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", service.create(user)));
    }

    @GetMapping("/{id}")
    public AppraisalService.AppraisalView get(@PathVariable long id, @AuthenticationPrincipal FamsUserPrincipal user) {
        return service.get(id, user);
    }

    @PostMapping("/{id}/submit")
    public Map<String, String> submit(@PathVariable long id, @AuthenticationPrincipal FamsUserPrincipal user,
                                      @RequestBody SubmitRequest body) {
        if (!body.declarationAccepted()) {
            throw new ValidationException(Map.of("declarationAccepted",
                    "You must accept the declaration to submit."));
        }
        return Map.of("status", service.submitChecked(id, user).name());
    }

    @PostMapping("/{id}/review/start")
    public Map<String, String> start(@PathVariable long id, @AuthenticationPrincipal FamsUserPrincipal user) {
        return act(id, user, WorkflowAction.Verb.START, null);
    }

    @PostMapping("/{id}/review/approve")
    public Map<String, String> approve(@PathVariable long id, @AuthenticationPrincipal FamsUserPrincipal user,
                                       @RequestBody(required = false) CommentRequest body) {
        return act(id, user, WorkflowAction.Verb.APPROVE, body == null ? null : body.comment());
    }

    // ---- messages from the Head of the Department to the faculty member ----

    @GetMapping("/{id}/messages")
    public List<MessageService.Message> messages(@PathVariable long id, @AuthenticationPrincipal FamsUserPrincipal user) {
        return messages.list(id, user);
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<MessageService.Message> send(@PathVariable long id, @AuthenticationPrincipal FamsUserPrincipal user,
                                                       @RequestBody MessageRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(messages.send(id, user, body.message()));
    }

    @PutMapping("/{id}/messages/{messageId}")
    public MessageService.Message editMessage(@PathVariable long id, @PathVariable long messageId,
                                              @AuthenticationPrincipal FamsUserPrincipal user, @RequestBody MessageRequest body) {
        return messages.edit(id, messageId, user, body.message());
    }

    @PostMapping("/{id}/messages/read")
    public Map<String, Integer> markRead(@PathVariable long id, @AuthenticationPrincipal FamsUserPrincipal user) {
        return Map.of("marked", messages.markRead(id, user));
    }

    private Map<String, String> act(long id, FamsUserPrincipal user, WorkflowAction.Verb verb, String comment) {
        return Map.of("status", service.transition(id, user, verb, comment).name());
    }
}
