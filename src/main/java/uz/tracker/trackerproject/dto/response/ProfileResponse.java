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
 * loanPayments = leftForSavings (with the debt, it picks the rule). The percentages multiply
 * savingsBase = max(stableIncome, salary received) + bonusThisMonth; each bucket's
 * normalMonthAmount is its percent of max(stableIncome, salary received), a month without a bonus.
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
    /**
     * What the percentages multiply this month — the engine's allocation base:
     * max(stableIncome, salary received) + bonus (see {@link #baseParts}). Until 2026-09-23 it was
     * leftForSavings + bonus; leftForSavings and loanPayments are still reported.
     */
    private BigDecimal savingsBase;
    /** How {@link #savingsBase} is made up; null while the stable income is unset. */
    private BaseParts baseParts;

    /** Which rule chose the percentages; null while the stable income is unset. */
    private Rule rule;
    /** DONATION, EMERGENCY, INVESTMENTS — always all three, in that order (0 when not asked for). */
    private List<Bucket> buckets;
    private BigDecimal totalPercent;
    private BigDecimal totalAmount;
    private BigDecimal normalMonthTotal;

    /** Next month's rule and percentages; null when both are the same as this month's. */
    private NextMonth nextMonth;

    /** What actually came in this month so far — earned income only. Present even without a stable income. */
    private IncomeThisMonth incomeThisMonth;
    /** What has been set aside this month, against the rule's targets. Present even without a stable income. */
    private AllocatedThisMonth allocatedThisMonth;

    /**
     * This month's income dated today or earlier, by the category it was recorded in (a salary
     * advance under Salary is its own line), largest first. Money that is not earned is left out:
     * borrowed money and loans paid back to the owner (their sums reported beside), transfers
     * between the owner's own wallets, a wallet check-in's surplus correction, and non-UZS pots.
     */
    @Getter @Builder
    public static class IncomeThisMonth {
        private BigDecimal total;
        private List<IncomeLine> lines;
        /** Σ of borrowed money (LOAN_RECEIVED) left out. */
        private BigDecimal excludedBorrowed;
        /** Σ of money paid back to the owner (LOAN_RETURNED_TO_ME) left out. */
        private BigDecimal excludedReturned;
    }

    @Getter @Builder
    public static class IncomeLine {
        /** The category's id; null for income recorded without a category. */
        private Long categoryId;
        /** The category's name ("Uncategorized" when there is none). */
        private String name;
        /** Its Uzbek name, when it has one. */
        private String nameUz;
        private BigDecimal amount;
        /** True when this income is in the savings base: the salary's category tree, bonus included. */
        private boolean inBase;
    }

    @Getter @Builder
    public static class AllocatedThisMonth {
        private BigDecimal total;
        /** total ÷ incomeThisMonth.total × 100, one decimal; null while the income is 0. */
        private BigDecimal percentOfIncome;
        /** total ÷ savingsBase × 100, one decimal; null while the base is 0 or unknown. */
        private BigDecimal percentOfBase;
        /** DONATION, EMERGENCY, INVESTMENTS always, in that order; then GOALS when anything went to one. */
        private List<AllocatedLine> lines;
    }

    @Getter @Builder
    public static class AllocatedLine {
        /** DONATION | EMERGENCY | INVESTMENTS | GOALS */
        private String bucket;
        /**
         * A bucket: exactly the {@code paid} the advisor reports for it (marks included). GOALS:
         * contributions to savings goals this month, dated today or earlier.
         */
        private BigDecimal amount;
        /** amount ÷ incomeThisMonth.total × 100, one decimal; null while the income is 0. */
        private BigDecimal percentOfIncome;
        /** amount ÷ savingsBase × 100, one decimal; null while the base is 0 or unknown. */
        private BigDecimal percentOfBase;
        /** The bucket's rule amount (= buckets[].amount); null for GOALS and while the stable income is unset. */
        private BigDecimal target;
        /** max(0, amount − target): set aside beyond the target; null without a target. */
        private BigDecimal over;
    }

    /** savingsBase = max(stableIncome, salaryReceived) + bonus. */
    @Getter @Builder
    public static class BaseParts {
        /** This month's salary so far: income in the salary's category tree, bonus left out, dated up to today. */
        private BigDecimal salaryReceived;
        private BigDecimal stableIncome;
        /** True while the stable income is the larger — before payday, or a smaller salary than Settings. */
        private boolean usesStableIncome;
        private BigDecimal bonus;
        /** The salary-tree income behind it, bonus included, by category, largest first. */
        private List<BaseLine> lines;
    }

    @Getter @Builder
    public static class BaseLine {
        private Long categoryId;
        private String name;
        private String nameUz;
        private BigDecimal amount;
    }

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
        /** percent × max(stableIncome, salary received) — the same bucket in a month without a bonus. */
        private BigDecimal normalMonthAmount;
    }

    @Getter @Builder
    public static class NextMonth {
        /** YYYY-MM. */
        private String month;
        private String reason;
        private BigDecimal loanPayments;
        private BigDecimal leftForSavings;
        /** percent × the stable income: next month before its salary and without a bonus. */
        private List<NextBucket> buckets;
    }

    @Getter @Builder
    public static class NextBucket {
        private String bucket;
        private BigDecimal percent;
        private BigDecimal normalMonthAmount;
    }
}
