package edu.svec.fams.reference;

import edu.svec.fams.auth.FamsUserPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration of the dropdown lists. Administrators only: the URL rule for /api/admin/** in SecurityConfig and the
 * annotation below both say so.
 */
@RestController
@RequestMapping("/api/admin/lists")
@PreAuthorize("hasRole('ADMIN')")
public class ReferenceController {

    private final ReferenceData data;

    public ReferenceController(ReferenceData data) { this.data = data; }

    /** Every list with all of its values, switched on or off. */
    @GetMapping
    public List<ReferenceData.ListView> lists() { return data.all(); }

    /** Body: {@code code}, {@code label}, optional {@code reportLabel}, and {@code parent} for a list that has parents. */
    @PostMapping("/{list}/values")
    @ResponseStatus(HttpStatus.CREATED)
    public ReferenceData.Value add(@AuthenticationPrincipal FamsUserPrincipal me, @PathVariable String list,
                                   @RequestBody Map<String, Object> body) {
        return data.add(me.id(), list, body);
    }

    /** Body: any of {@code label}, {@code reportLabel}, {@code active}. The place of a value is changed with {@code move}. */
    @PutMapping("/values/{id}")
    public ReferenceData.Value update(@AuthenticationPrincipal FamsUserPrincipal me, @PathVariable long id,
                                      @RequestBody Map<String, Object> body) {
        return data.update(me.id(), id, body);
    }

    /** Body: {@code {"direction": "UP" | "DOWN"}}: one place earlier or later among the active values; saved at once. */
    @PostMapping("/values/{id}/move")
    public ReferenceData.Value move(@AuthenticationPrincipal FamsUserPrincipal me, @PathVariable long id,
                                    @RequestBody Map<String, Object> body) {
        return data.move(me.id(), id, body.get("direction"));
    }
}
