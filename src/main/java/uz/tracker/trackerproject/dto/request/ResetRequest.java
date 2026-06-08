package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Body for the factory-reset endpoint. The current account password is re-verified
 * before any data is wiped — this is a destructive, irreversible action.
 */
@Getter
@Setter
public class ResetRequest {
    @NotBlank(message = "Password is required")
    private String password;
}
