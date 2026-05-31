package uz.tracker.trackerproject.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.AuthRequest;
import uz.tracker.trackerproject.dto.response.TokenResponse;
import uz.tracker.trackerproject.entity.AppUser;
import uz.tracker.trackerproject.exception.UnauthorizedException;
import uz.tracker.trackerproject.repository.AppUserRepository;
import uz.tracker.trackerproject.security.JwtService;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    @Transactional(readOnly = true)
    public boolean needsSignup() {
        return users.count() == 0;
    }

    /** First-run only — creates the single account, then signup is closed. */
    @Transactional
    public TokenResponse signup(AuthRequest req) {
        if (users.count() > 0) {
            throw new IllegalArgumentException("An account already exists — please log in.");
        }
        String username = req.getUsername() == null ? "" : req.getUsername().trim();
        String password = req.getPassword() == null ? "" : req.getPassword();
        if (username.isBlank()) throw new IllegalArgumentException("Username is required.");
        if (password.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters.");

        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(password));
        users.save(u);
        return tokensFor(username);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(AuthRequest req) {
        String username = req.getUsername() == null ? "" : req.getUsername().trim();
        AppUser u = users.findByUsername(username).orElse(null);
        String raw = req.getPassword() == null ? "" : req.getPassword();
        if (u == null || !encoder.matches(raw, u.getPasswordHash())) {
            throw new UnauthorizedException("Invalid username or password.");
        }
        return tokensFor(username);
    }

    /** Exchange a valid refresh token for a fresh access + refresh pair (rotation). */
    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException("Missing refresh token.");
        }
        Claims claims;
        try {
            claims = jwt.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid or expired refresh token.");
        }
        if (!jwt.isType(claims, JwtService.TYPE_REFRESH)) {
            throw new UnauthorizedException("Not a refresh token.");
        }
        String username = claims.getSubject();
        if (users.findByUsername(username).isEmpty()) {
            throw new UnauthorizedException("Account no longer exists.");
        }
        return tokensFor(username);
    }

    private TokenResponse tokensFor(String username) {
        return TokenResponse.builder()
                .accessToken(jwt.generateAccess(username))
                .refreshToken(jwt.generateRefresh(username))
                .tokenType("Bearer")
                .expiresIn(jwt.getAccessTtlSeconds())
                .build();
    }
}
