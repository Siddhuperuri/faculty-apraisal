package edu.svec.fams.console;

import edu.svec.fams.auth.FamsUserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reviewers' consoles. Each is reachable by its own role only: the URL rule in SecurityConfig and the annotation
 * here both say so. (The administrator's overview is part of the admin area.)
 */
@RestController
@RequestMapping("/api")
public class ConsoleController {

    private final ConsoleService service;

    public ConsoleController(ConsoleService service) { this.service = service; }

    @GetMapping("/hod/console")
    @PreAuthorize("hasRole('HOD')")
    public ConsoleService.HodConsole hod(@AuthenticationPrincipal FamsUserPrincipal user,
                                         @RequestParam(required = false) Long academicYearId) {
        return service.hod(user, academicYearId);
    }

    @GetMapping("/principal/console")
    @PreAuthorize("hasRole('PRINCIPAL')")
    public ConsoleService.PrincipalConsole principal(@RequestParam(required = false) Long academicYearId) {
        return service.principal(academicYearId);
    }

    /** The Director Technical decides at the Principal's level, so the console shows the same college-wide picture. */
    @GetMapping("/director/console")
    @PreAuthorize("hasRole('DIRECTOR')")
    public ConsoleService.PrincipalConsole director(@RequestParam(required = false) Long academicYearId) {
        return service.principal(academicYearId);
    }
}
