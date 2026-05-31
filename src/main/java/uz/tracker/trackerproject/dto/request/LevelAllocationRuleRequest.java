package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One row of the Level 2–6 allocation editor. Percents are nullable: null/blank = the
 * bucket isn't recommended at this sub-level. A row with all four null is treated as
 * "unset" and removed server-side.
 */
@Getter @Setter
public class LevelAllocationRuleRequest {

    @NotBlank
    private String subLevel;   // e.g. "3.2"

    private BigDecimal donationPercent;
    private BigDecimal emergencyPercent;
    private BigDecimal investmentsPercent;
    private BigDecimal stocksPercent;
    private String note;
}
