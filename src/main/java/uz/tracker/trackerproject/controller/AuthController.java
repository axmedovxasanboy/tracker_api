package uz.tracker.trackerproject.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.AuthRequest;
import uz.tracker.trackerproject.dto.request.RefreshRequest;
import uz.tracker.trackerproject.dto.response.AuthStatusResponse;
import uz.tracker.trackerproject.dto.response.TokenResponse;
import uz.tracker.trackerproject.service.AuthService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** Public: tells the client whether to show first-run signup or the login screen. */
    @GetMapping("/status")
    public AuthStatusResponse status() {
        return AuthStatusResponse.builder().needsSignup(authService.needsSignup()).build();
    }

    @PostMapping("/signup")
    public TokenResponse signup(@Valid @RequestBody AuthRequest req) {
        return authService.signup(req);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody AuthRequest req) {
        return authService.login(req);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req.getRefreshToken());
    }

    /** Authenticated: validates the access token and returns the current username. */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of("username", authentication.getName());
    }
}
