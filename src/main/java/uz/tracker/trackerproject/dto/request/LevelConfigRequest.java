package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Save one level's editable configuration. Only the user's current (unlocked) level may be
 * saved — enforced server-side. {@code rules} carries the level's sub-level percentages
 * (ignored for Level 1, whose percentages are built-in).
 */
@Getter @Setter
public class LevelConfigRequest {

    @NotNull
    private Integer level;

    /** Minimum leftover in UZS. */
    private BigDecimal minLeftover;

    /** Any day in the month to lock until; normalised to the 1st. Null = stays editable. */
    private LocalDate expirationMonth;

    private List<LevelAllocationRuleRequest> rules;
}
