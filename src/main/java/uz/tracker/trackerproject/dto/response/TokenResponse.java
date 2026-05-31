package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter @Builder
public class TokenResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType;     // "Bearer"
    private long expiresIn;        // access-token TTL in seconds
}
