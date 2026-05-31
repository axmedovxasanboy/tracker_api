package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Full allocation-rules view for the editor. Covers Levels 1–6 so the user can compare;
 * Level 1 percentages are a built-in read-only reference. All money values are in UZS
 * (the tier system's anchor currency). Only the user's current level is editable, and only
 * when it isn't locked by an expiration month.
 */
@Getter @Builder
public class AllocationRulesViewResponse {

    private Integer currentLevel;
    private String currentSubLevel;
    private boolean missingStableIncome;
    private List<LevelView> levels;

    @Getter @Builder
    public static class LevelView {
        private int level;
        private BigDecimal incomeLow;       // UZS — lower bound of this level's left-money range
        private BigDecimal incomeHigh;      // UZS — upper bound
        private BigDecimal minLeftover;     // UZS — configured (Level 1 defaults to 5M)
        private String expirationMonth;     // YYYY-MM or null
        private boolean locked;             // expiration set and not yet reached
        private boolean editable;           // == current level and not locked
        private boolean builtIn;            // Level 1 — percentages are read-only
        private List<SubLevelView> subLevels;
    }

    @Getter @Builder
    public static class SubLevelView {
        private String subLevel;            // e.g. "3.2"
        private String debtLabel;           // "No debt" / "Manageable (< 70%)" / "Heavy (≥ 70%)"
        private BigDecimal donationPercent;
        private BigDecimal emergencyPercent;
        private BigDecimal investmentsPercent;
        private BigDecimal stocksPercent;
    }
}
