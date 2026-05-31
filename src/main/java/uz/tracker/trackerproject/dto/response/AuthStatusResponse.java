package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter @Builder
public class AuthStatusResponse {
    /** True when no account exists yet — the client should show first-run signup. */
    private boolean needsSignup;
}
