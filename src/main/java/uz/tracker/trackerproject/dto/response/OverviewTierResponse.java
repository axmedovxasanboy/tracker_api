package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * Snapshot of the user's monthly financial tier. Every amount is UZS, the reporting currency —
 * nothing converts, and foreign cash pots never enter these figures.
 *
 * Level math (driven by leftMoney = the month's income − mandatorySubscriptions, evaluated in UZS;
 * LEVELS-ALLOCATION-SPEC, 2026-10-01):
 *   < 15M    → level 1
 *   < 30M    → level 2
 *   < 45M    → level 3
 *   >= 45M   → level 4 (no upper limit)
 *   level 5  → while a recorded Level 5 period covers the month (earned by pay, not by leftMoney)
 *
 * Sub-level (every level):
 *   X.1 → debtPayments == 0
 *   X.2 → 0 < debtPayments/income <= 0.70
 *   X.3 → debtPayments/income > 0.70
 *
 * Every level picks one of seven situations the way Level 1 always did, and reads its percentages
 * from that level's rules version in force for the month.
 */
@Getter @Builder
public class OverviewTierResponse {

    private Currency currency;

    /** Stable monthly income from Settings. */
    private BigDecimal income;

    /** Sum of active MonthlyPayment amounts ("mandatory to self"). */
    private BigDecimal mandatorySubscriptions;

    /** income − mandatorySubscriptions. The level is computed from this. */
    private BigDecimal leftMoney;

    /**
     * What the bucket percentages are applied to: {@link #income} + {@link #bonusIncome} (owner's
     * decision, 2026-09-30) — a recorded salary or advance never moves it, only a bonus does. It used
     * to be max(income, salaryReceived) + bonus (2026-09-23), and before that max(0, leftMoney −
     * debtPayments) + bonus; the level and the percentages are still chosen from {@link #leftMoney}
     * and the debt.
     */
    private BigDecimal allocationBase;

    /**
     * The salary received for this month: regular income in the salary's category tree (the root
     * above the bonus categories — for the owner Salary, with Avans), bonus categories left out,
     * counted in its accounting month (Transaction.salaryMonth ?? its date's month — September's
     * salary paid on 3 October is September's), arrived up to today. Reported for the advisor and
     * the daily walk; it is not part of {@link #allocationBase}.
     */
    private BigDecimal salaryReceived;

    /**
     * Income received this month in a bonus-flagged category (or one whose parent is flagged). It is
     * already inside {@link #allocationBase}, so each bucket's target rises by its percentage of the
     * bonus; the level, sub-level and tight/comfortable split still come from stable income alone.
     * Zero when there was none.
     */
    private BigDecimal bonusIncome;

    /**
     * This month's debt charge: bank installments + the month's asks on borrowed money and debts
     * — a MONTHLY loan's plan, or an ASAP loan's or debt's ASAP ask (all of what was left at the
     * month's start when that is at most 70% of the stable income, else 34% of it). Drives the sub-level ratio and Level 1's tight-vs-comfortable split; since
     * 2026-09-23 it no longer lowers {@link #allocationBase}.
     */
    private BigDecimal debtPayments;

    private DebtBreakdown debtBreakdown;

    /** debtPayments / income. Null when income is zero. */
    private BigDecimal debtRatio;

    /** 1..6 — null when above the tier ceiling or income is missing. */
    private Integer level;

    /** "1.1" | "1.2" | "1.3" — null when level is not 1 (yet). */
    private String subLevel;

    /** Human-readable badge label e.g. "Level 1.2". */
    private String levelLabel;

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
        /** Σ this month's asks on borrowed money (LoanTaken): MONTHLY plans and ASAP asks. */
        private BigDecimal loansTaken;
        /** Σ this month's ASAP asks on debts (Debt). */
        private BigDecimal debts;
        /** Σ this month's MONTHLY plans (part of {@link #loansTaken}). */
        private BigDecimal monthlyPlans;
        /**
         * The personal debt that counts when the savings rule is chosen: every ASAP ask, plus the
         * MONTHLY plans only when they are more than 10% of the stable income. Zero with a bank loan
         * means the bank-loan-only rule; zero without one, the no-debt rule.
         */
        private BigDecimal countedForRule;
    }
}
