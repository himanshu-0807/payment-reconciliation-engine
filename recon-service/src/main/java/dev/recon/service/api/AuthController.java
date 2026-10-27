package dev.recon.service.api;

import dev.recon.service.audit.AuditLog;
import dev.recon.service.security.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Login and identity")
public class AuthController {

    private final AuthenticationManager auth;
    private final TokenService tokens;
    private final AuditLog audit;

    public AuthController(AuthenticationManager auth, TokenService tokens, AuditLog audit) {
        this.auth = auth;
        this.tokens = tokens;
        this.audit = audit;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Exchange username/password for a JWT bearer token")
    public Dtos.LoginResponse login(@Valid @RequestBody Dtos.LoginRequest req) {
        Authentication a = auth.authenticate(new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        String role = a.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "");
        TokenService.IssuedToken t = tokens.issue(a.getName(), role);
        audit.record(a.getName(), "LOGIN", "USER", a.getName(), "role " + role);
        return new Dtos.LoginResponse(t.token(), "Bearer", t.expiresAt(), a.getName(), role);
    }

    @GetMapping("/me")
    @Operation(summary = "Who am I")
    public Dtos.Me me(@AuthenticationPrincipal Jwt jwt) {
        return new Dtos.Me(jwt.getSubject(), jwt.getClaimAsStringList("roles"));
    }
}
