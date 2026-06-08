package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * Snapshot of the user's monthly financial tier. All monetary values are converted
 * into {@link #currency} from their native currencies using the FX rates in Settings.
 *
 * Level math (driven by leftMoney = income − mandatorySubscriptions, evaluated in UZS):
 *   < 15M    → level 1
 *   < 30M    → level 2
 *   < 45M    → level 3
 *   < 60M    → level 4
 *   < 75M    → level 5
 *   < 90M    → level 6
 *   >= 90M   → null (above tier ceiling)
 *
 * Sub-level (Level 1 only, for now):
 *   1.1 → debtPayments == 0
 *   1.2 → 0 < debtPayments/income < 0.70
 *   1.3 → debtPayments/income >= 0.70
 *
 * Levels 2-6 return subLevel = null until the rules are defined.
 */
@Getter @Builder
public class OverviewTierResponse {

    private Currency currency;

    /** Stable monthly income from Settings, converted into {@link #currency}. */
    private BigDecimal income;

    /** Sum of active MonthlyPayment amounts ("mandatory to self"). */
    private BigDecimal mandatorySubscriptions;

    /** income − mandatorySubscriptions. The level is computed from this. */
    private BigDecimal leftMoney;

    /**
     * "Left balance" = this month's available money (carryover + income earned this month). The
     * bucket allocation percentages are applied to THIS, not to stable income. The tier level and
     * tight/comfortable split still come from stable income.
     */
    private BigDecimal allocationBase;

    /** bankLoans + loansTaken + debts — used for the sub-level debt-ratio rule. */
    private BigDecimal debtPayments;

    private DebtBreakdown debtBreakdown;

    /** debtPayments / income. Null when income is zero. */
    private BigDecimal debtRatio;

    /** 1..6 — null when above the tier ceiling or income is missing. */
    private Integer level;

    /** "1.1" | "1.2" | "1.3" — null when level is not 1 (yet). */
    private String subLevel;

    /** Human-readable badge label e.g. "Level 1.2" or "Above tier 6". */
    private String levelLabel;

    /** True when FX rates are unset and built-in defaults are in effect. */
    private boolean fxRatesUsingDefaults;

    /** True when the user hasn't configured Settings.monthlyStableIncome. */
    private boolean missingStableIncome;

    /**
     * True when the viewed month is before the configured allocation tracking start month.
     * Guidance is paused (no payment asks); the client greys the whole dashboard.
     */
    private boolean beforeTrackingStart;

    /** YYYY-MM the allocation tracking starts from, or null when not configured. */
    private String trackingStartMonth;

    /**
     * True when one or more active mandatory subscriptions are NOT yet fully paid for the
     * viewed month. While true the level / sub-level / action items / allocation are withheld —
     * the user must pay subscriptions first (see {@link #pendingSubscriptions}).
     */
    private boolean subscriptionsPending;

    /** The unpaid (or partially paid) active subscriptions for the viewed month. Empty when none. */
    private List<PendingSubscription> pendingSubscriptions;

    /**
     * Recommended bucket allocation for this tier (Donation / Emergency / Investments /
     * Stocks). Hard-coded for Level 1; null/empty notes for Levels 2-6 until the user
     * configures their own percentages.
     */
    private TierAllocation allocation;

    /** One unpaid/partly-paid subscription for the viewed month, in its own native currency. */
    @Getter @Builder
    public static class PendingSubscription {
        private Long id;
        private String name;
        private Currency currency;
        private BigDecimal amount;   // the subscription's monthly amount
        private BigDecimal paid;     // recorded this month so far (native currency)
    }

    @Getter @Builder
    public static class DebtBreakdown {
        /** Sum of BankLoan.monthlyPayment for active bank loans. */
        private BigDecimal bankLoans;
        /** Σ (remaining / monthsUntilDue) for all unpaid LoanTaken records. */
        private BigDecimal loansTaken;
        /** Σ (remaining / monthsUntilDue) for all unpaid Debt records. */
        private BigDecimal debts;
    }
}
