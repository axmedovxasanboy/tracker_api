package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * The profile: the owner's level and how their savings percentages come from their stable income.
 * Built for the web's Profile page from the same tier the Plan and the advisor use, so every figure
 * here is one they already quote — the page never needs the engine's internals. Money is UZS.
 *
 * <p>The chain it explains: stableIncome − monthlyBills = leftAfterBills (which sets the level) −
 * loanPayments = leftForSavings, + bonusThisMonth = savingsBase; each bucket is its percent of
 * savingsBase this month, and of leftForSavings in a month without a bonus.
 */
@Getter @Builder
public class ProfileResponse {

    private String username;
    /** YYYY-MM. */
    private String month;
    /** True until a monthly stable income is set: then there is no level, rule, buckets or next month. */
    private boolean missingStableIncome;

    /** 1..6 — the 15M step alone, never the sub-level (1.2 reads as 1). Null above the top step. */
    private Integer level;
    /** True when leftAfterBills is at or above the top breakpoint (90M): no level, no guidance. */
    private boolean aboveCeiling;
    /** The current band's lower bound (the top breakpoint when above the ceiling). */
    private BigDecimal levelFrom;
    /** Where the next band starts — at level 6 the ceiling itself; null above it. */
    private BigDecimal nextLevelAt;

    private BigDecimal stableIncome;
    /** The active monthly bills the level is measured after. */
    private BigDecimal monthlyBills;
    /** stableIncome − monthlyBills: this is what sets the level. */
    private BigDecimal leftAfterBills;
    /** This month's bank installments + debt charge (repayment plans, else 34% of the original). */
    private BigDecimal loanPayments;
    /** max(0, leftAfterBills − loanPayments). */
    private BigDecimal leftForSavings;
    private BigDecimal bonusThisMonth;
    /** leftForSavings + bonusThisMonth — what the percentages multiply this month. */
    private BigDecimal savingsBase;

    /** Which rule chose the percentages; null while the stable income is unset. */
    private Rule rule;
    /** DONATION, EMERGENCY, INVESTMENTS — always all three, in that order (0 when not asked for). */
    private List<Bucket> buckets;
    private BigDecimal totalPercent;
    private BigDecimal totalAmount;
    private BigDecimal normalMonthTotal;

    /** Next month's rule and percentages; null when both are the same as this month's. */
    private NextMonth nextMonth;

    @Getter @Builder
    public static class Rule {
        /**
         * NO_DEBT | BANK_LOAN_COMFORTABLE | BANK_LOAN_TIGHT | DEBTS_COMFORTABLE | DEBTS_TIGHT |
         * BANK_AND_DEBTS | HEAVY_DEBT — Level 1's built-in rules; CUSTOM — the owner's own rule for a
         * Level 2–6 sub-level; NO_RULE — none configured for it (and above the ceiling).
         */
        private String reason;
        /** The tight/comfortable threshold, when the rule depends on it; else null. */
        private BigDecimal cutoff;
    }

    @Getter @Builder
    public static class Bucket {
        /** DONATION | EMERGENCY | INVESTMENTS */
        private String bucket;
        private BigDecimal percent;
        /** percent × savingsBase — the month's target, the same figure as the advisor's. */
        private BigDecimal amount;
        /** percent × leftForSavings — the same bucket in a month without a bonus. */
        private BigDecimal normalMonthAmount;
    }

    @Getter @Builder
    public static class NextMonth {
        /** YYYY-MM. */
        private String month;
        private String reason;
        private BigDecimal loanPayments;
        private BigDecimal leftForSavings;
        private List<NextBucket> buckets;
    }

    @Getter @Builder
    public static class NextBucket {
        private String bucket;
        private BigDecimal percent;
        private BigDecimal normalMonthAmount;
    }
}
