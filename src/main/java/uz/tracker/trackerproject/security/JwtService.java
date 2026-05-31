package uz.tracker.trackerproject.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

/**
 * HS256 JWT issue/verify for the single-account auth. Two token kinds, distinguished by a
 * {@code type} claim: short-lived {@code access} (sent as a Bearer header) and longer-lived
 * {@code refresh} (exchanged at /auth/refresh for a fresh access token).
 *
 * The signing key is SHA-256-derived from {@code app.jwt.secret}, so any secret length is
 * accepted (mirrors CardNumberCipher). Changing the secret invalidates all issued tokens.
 */
@Component
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-ttl-seconds:900}") long accessTtlSeconds,
            @Value("${app.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        this.key = Keys.hmacShaKeyFor(sha256(secret));
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public String generateAccess(String username) {
        return build(username, TYPE_ACCESS, accessTtlSeconds);
    }

    public String generateRefresh(String username) {
        return build(username, TYPE_REFRESH, refreshTtlSeconds);
    }

    private String build(String username, String type, long ttlSeconds) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("type", type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /** Parse + verify signature/expiry. Throws {@link JwtException} when invalid/expired. */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public boolean isType(Claims claims, String expectedType) {
        return expectedType.equals(claims.get("type", String.class));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
