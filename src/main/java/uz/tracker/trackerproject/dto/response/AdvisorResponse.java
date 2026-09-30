package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The advisor's answer for one day: what the owner has, what is still coming in, what this month
 * still asks for, what is left free after that, and what to do next. Both the web Home screen and
 * the Telegram bot render this one response, so they can never give different advice.
 *
 * <p>Every figure is UZS and comes from the same services the Plan uses — the advisor adds no
 * allocation math of its own. The one difference from the Plan is that the set-aside amounts are
 * reported even while subscriptions are unpaid ({@link #setAsideAfterBills} says so), because
 * "how much do I need to set aside" is a question the owner asks before the rent is paid too.
 */
@Getter @Builder
public class AdvisorResponse {

    private LocalDate date;
    /** YYYY-MM of {@link #date}. */
    private String month;
    /**
     * YYYY-MM the add-income form should pre-select as "salary for which month": the previous month
     * while {@code date} is in the first 10 days and that month's salary received (counted by its
     * accounting month) is under half the stable income; else {@link #month}.
     */
    private String suggestedSalaryMonth;
    private Currency currency;

    /** True until a monthly stable income is set: nothing but the wallets can be advised on. */
    private boolean missingStableIncome;

    // ── You have ────────────────────────────────────────────────────────────

    /** Σ of every UZS wallet's balance as the app computes it on {@link #date}. */
    private BigDecimal have;
    private List<Wallet> wallets;
    /** The last day the wallets were reconciled (a check-in or a month close); null if never. */
    private LocalDate balanceCheckedOn;
    /** Days since {@link #balanceCheckedOn}; null if never. */
    private Integer balanceCheckedDaysAgo;

    // ── Coming in ───────────────────────────────────────────────────────────

    /** The monthly stable income from Settings; zero while it is unset. */
    private BigDecimal salaryExpected;
    /**
     * Regular income recorded this month, bonus-category income excluded. Measured, not asked:
     * the owner records the salary when it arrives and the advisor stops counting it as coming.
     */
    private BigDecimal salaryReceived;
    /** max(0, {@link #salaryExpected} − {@link #salaryReceived}). */
    private BigDecimal salaryComing;
    /** Bonus-category income recorded this month (already in {@link #have}). */
    private BigDecimal bonusReceived;
    /** Money lent out and not yet returned. Shown, never counted into {@link #free}. */
    private List<Owed> owedToYou;
    private BigDecimal owedToYouTotal;

    // ── Still this month ────────────────────────────────────────────────────

    /** Unpaid subscriptions, then the bank installment and the debt asks, each by what is left. */
    private List<Bill> bills;
    private BigDecimal billsLeft;
    /** Each allocation bucket with something still to set aside this month. */
    private List<SetAside> setAside;
    private BigDecimal setAsideLeft;
    /**
     * True while the Plan asks for the bills first: a subscription is unpaid or a debt ask is below
     * its unlock amount. The set-aside figures are still real; they are just the step after.
     */
    private boolean setAsideAfterBills;

    // ── Free ────────────────────────────────────────────────────────────────

    /**
     * {@link #have} + {@link #salaryComing} − {@link #billsLeft} − {@link #setAsideLeft}. Negative
     * when the month asks for more than there is. Null while the stable income is unset.
     */
    private BigDecimal free;

    /** What to do next, most urgent first. */
    private List<Suggestion> suggestions;

    // ── Per day (added for the new Home; every field above is unchanged) ─────

    /**
     * How much can be spent a day without running short, counting the next salaries, bills, loan
     * payments and savings up to {@link Daily#until} — and how that compares with what is actually
     * spent. Null while the stable income is unset. See {@code DailyAdviceService}.
     */
    private Daily daily;

    /**
     * Every allocation bucket with a target this month, INCLUDING the ones already met — the same
     * figures as {@link #setAside}, which lists only what is still to put aside — then one GOAL row
     * per savings goal with a monthly payment that has not reached its target (goals are outside
     * the plan's percentages, so {@link #setAside} and {@link #free} leave them out). Empty while the
     * stable income is unset.
     */
    private List<SavingsRow> savingsThisMonth;

    // ── A normal month, the goals, and what is owed (added for the usability fixes) ─────────

    /**
     * Does a month WITHOUT a bonus have room for what is asked of it? The server decides; clients
     * only print. Null while the monthly income is unset.
     */
    private Means means;
    /** Every savings goal, plans first, then wishes; empty when there is none. */
    private List<Goal> goals;
    /** What is still to repay, what could not be counted, and what is owed to the owner. Never null. */
    private Owe owe;

    /**
     * A normal month's arithmetic. "Next month" is the calendar month after {@link AdvisorResponse#date}'s,
     * so a plan starting next month is counted and this month's part-paid state is not.
     */
    @Getter @Builder
    public static class Means {
        /** Settings' monthly income. */
        private BigDecimal income;
        /** Active monthly bills. */
        private BigDecimal bills;
        /** Next month's loan payments, as the Plan counts them: bank installments, plans and ASAP asks. */
        private BigDecimal loanPayments;
        /** Next month's rule percentages × income (no bonus, no carry-over). */
        private BigDecimal setAside;
        /** Σ monthly payments of the PLANS asked for next month, each capped at what finishes it. */
        private BigDecimal goals;
        /** income − bills − loanPayments − setAside − goals; may be negative. */
        private BigDecimal leftToLive;
        /** income − bills − loanPayments − setAside; may be negative. */
        private BigDecimal roomForGoals;
        /** {@code daily.paceDaily} × 30; null when there is no pace yet. */
        private BigDecimal paceMonthly;
        /**
         * DOES_NOT_FIT: leftToLive &lt; 0. TIGHT: leftToLive ≥ 0 but below paceMonthly (pace known).
         * FITS: otherwise.
         */
        private String verdict;
    }

    /** One savings goal as the Savings page lists it. */
    @Getter @Builder
    public static class Goal {
        private Long id;
        private String name;
        /** PLAN | WISH (= InvestmentResponse.goalKind). */
        private String kind;
        /** Null when the goal has none. */
        private BigDecimal target;
        /** What is in it now. */
        private BigDecimal value;
        /** The stored monthly payment — kept even for a wish; null when never set. */
        private BigDecimal monthly;
        /** YYYY-MM the payments start; null when not set. */
        private String startMonth;
        /** YYYY-MM of the deadline; null when not set. */
        private String deadline;
        /**
         * Null for a wish. For a plan, the first that applies: DONE (value ≥ target) · DOES_NOT_FIT
         * (means.verdict is DOES_NOT_FIT) · BEHIND (it has a deadline the monthly payment will not
         * meet) · ON_TRACK.
         */
        private String status;
        /** BEHIND only: the monthly payment that would meet the deadline. */
        private BigDecimal neededMonthly;
    }

    @Getter @Builder
    public static class Owe {
        /**
         * Σ still to repay on every loan whose amount is known: borrowed money, debts, and bank
         * loans that have an end date.
         */
        private BigDecimal leftToRepay;
        /** Loans left out of {@link #leftToRepay} because the amount cannot be known; empty when none. */
        private List<NotCounted> notCounted;
        /** The part of {@link #leftToRepay} on loans repaid as fast as possible. */
        private BigDecimal toRepayFast;
        /** = owedToYouTotal. */
        private BigDecimal owedToYou;
    }

    @Getter @Builder
    public static class NotCounted {
        /** BANK | LOAN | DEBT — the words {@code upcoming.kind} uses. */
        private String kind;
        private Long refId;
        private String name;
    }

    @Getter @Builder
    public static class Wallet {
        /** CARD | CASH */
        private String type;
        /** Null for cash. */
        private Long cardId;
        private String label;
        private BigDecimal balance;
    }

    @Getter @Builder
    public static class Owed {
        private Long id;
        private String name;
        private BigDecimal amount;
        private LocalDate expectedOn;
    }

    @Getter @Builder
    public static class Bill {
        /** SUBSCRIPTION | BANK | LOAN_PLAN | DEBTS */
        private String kind;
        /** The subscription's id for SUBSCRIPTION; null otherwise. */
        private Long refId;
        /** The subscription's name for SUBSCRIPTION; null otherwise (clients label the kind). */
        private String name;
        /** What is still to pay this month. */
        private BigDecimal amount;
        private BigDecimal paid;
        private BigDecimal target;
    }

    @Getter @Builder
    public static class SetAside {
        /** DONATION | EMERGENCY | INVESTMENTS */
        private String bucket;
        private BigDecimal percent;
        /** This month's rule amount (percent × base); 0 for a bucket the rule does not ask for. */
        private BigDecimal target;
        /** What earlier months left unpaid in this bucket (never negative: an overpayment never carries). */
        private BigDecimal carried;
        private BigDecimal paid;
        /** What is still to set aside this month: max(0, target + carried − paid). */
        private BigDecimal remaining;
    }

    /**
     * One bucket's set-aside this month, met or not — or one savings goal's monthly payment
     * ({@code bucket} GOAL), listed after the buckets while the goal is short of its target.
     */
    @Getter @Builder
    public static class SavingsRow {
        /** DONATION | EMERGENCY | INVESTMENTS | GOAL */
        private String bucket;
        /** GOAL: the goal's (Investment) id; null for a bucket. */
        private Long refId;
        /** GOAL: the goal's name; null for a bucket. */
        private String name;
        /** The bucket's percentage; null for a GOAL, whose payment is a set amount. */
        private BigDecimal percent;
        /**
         * A bucket: this month's rule amount (0 when the rule does not ask for it, though it carries).
         * GOAL: the monthly payment — never more than what finishes the goal.
         */
        private BigDecimal target;
        /** A bucket: what earlier months left unpaid in it (never negative). GOAL: null — goals do not carry. */
        private BigDecimal carried;
        /** GOAL: contributions to it this month, dated today or earlier. */
        private BigDecimal paid;
        /** max(0, target + carried − paid); zero once the bucket is met. */
        private BigDecimal remaining;
    }

    /**
     * The daily figure and everything behind it. Money is UZS; every date is the owner's local day.
     *
     * <p>For each day c from {@link AdvisorResponse#date} to {@link #until}:
     * net(c) = have + income due by c − must-pays due by c − savings reserved by c, and
     * {@link #safePerDay} is the smallest net(c) ÷ (days from today to c, inclusive), rounded down
     * to 1,000 — the most that can be spent every day without any later payment going unmet.
     */
    @Getter @Builder
    public static class Daily {
        /** Rounded DOWN to 1,000; 0 when {@link #shortBy} is set. */
        private BigDecimal safePerDay;
        /** The horizon's last day (inclusive): the day before the second upcoming main payday. */
        private LocalDate until;
        /** The day that sets {@link #safePerDay} (earliest on ties); the shortfall day when short. */
        private LocalDate tightestOn;
        /** The sum behind {@link #safePerDay}, for the window [today, {@link #tightestOn}]. */
        private Breakdown breakdown;
        /** Average everyday spending per day over [{@link #paceFrom}, {@link #paceTo}]; null under 7 days of data. */
        private BigDecimal paceDaily;
        private LocalDate paceFrom;
        private LocalDate paceTo;
        /** First day the money no longer covers what is due if {@link #paceDaily} is spent every day; null if it lasts. */
        private LocalDate runsOutOn;
        /** Set when even spending nothing leaves the must-pays and savings unmet. */
        private ShortBy shortBy;
        /** Must-pays dated today … today + 34, overdue ones dated today; date ascending. */
        private List<Upcoming> upcoming;
        /** Projected income in [today, {@link #until}]; date ascending. */
        private List<IncomePart> incomes;
        /**
         * Which state it is, so no client re-derives it: SHORT when {@link #shortBy} is set; else
         * OVER_PACE when {@link #runsOutOn} is set; else OK.
         */
        private String verdict;
        /**
         * Why, for OVER_PACE only (else null). GOALS: {@link #safePerDayNoGoals} ≥ {@link #paceDaily}
         * — without the plans' reservations the pace would hold. SAVINGS: not GOALS, and
         * {@link #safePerDayNoSavings} ≥ paceDaily. PACE: neither — too high even with nothing set aside.
         */
        private String cause;
        /** {@link #safePerDay} by the same walk with every goal reservation removed. Same rounding. */
        private BigDecimal safePerDayNoGoals;
        /** The same with every savings reservation removed: the rule's buckets, what they carry, and goals. */
        private BigDecimal safePerDayNoSavings;
    }

    /** net = have + comingIn − goingOut − savings, over {@code days} days. */
    @Getter @Builder
    public static class Breakdown {
        private BigDecimal have;
        private BigDecimal comingIn;
        private BigDecimal goingOut;
        private BigDecimal savings;
        /** The part of {@link #savings} that is the rule's buckets and their carry-over. */
        private BigDecimal setAside;
        /** The part of {@link #savings} that is goals. setAside + goals = savings. */
        private BigDecimal goals;
        private BigDecimal net;
        private int days;
    }

    @Getter @Builder
    public static class ShortBy {
        /** The day the money is lowest. */
        private LocalDate date;
        /** How much is missing on {@link #date} even with no spending at all. */
        private BigDecimal amount;
    }

    /**
     * One must-pay on one day: either still owed, or a payment the owner already recorded for that
     * day ({@link #recorded}). Both are money leaving the wallets on {@link #date}; only an owed one
     * is something to pay.
     */
    @Getter @Builder
    public static class Upcoming {
        private LocalDate date;
        /** BILL | BANK | LOAN (borrowed money, MONTHLY or ASAP) | DEBT (a Debt row) */
        private String kind;
        /** The MonthlyPayment / BankLoan / LoanTaken / Debt id — ids of different kinds collide. */
        private Long refId;
        private String name;
        private BigDecimal amount;
        /** True when it was due earlier this month and is still unpaid — it is then dated today. */
        private boolean overdue;
        /**
         * True when this row is a payment the owner ALREADY recorded, dated later this month: it has
         * not left the wallets yet (they are as of today), so it stays in the list on its own day —
         * but paying it again would record it twice. False for a due that is still owed.
         */
        private boolean recorded;
        /**
         * True for an ASAP ask — borrowed money without a plan (kind LOAN) or a debt (kind DEBT) to
         * pay back as fast as possible; this month's is dated today, money in hand, and later
         * months' fall on their main payday. False for bills, bank installments and MONTHLY plans.
         */
        private boolean asap;
    }

    @Getter @Builder
    public static class IncomePart {
        private LocalDate date;
        private String name;
        private BigDecimal amount;
    }

    /**
     * One piece of advice. {@code code} is a translation key the client renders with
     * {@code params}; {@code text} is the same sentence in English for a client without it.
     * Amounts are not inside {@code params} — {@link #amount} carries the number and each client
     * formats it its own way. The per-day warnings are not suggestions: see {@code Daily.shortBy}
     * and {@code Daily.runsOutOn}.
     */
    @Getter @Builder
    public static class Suggestion {
        private String code;
        private Map<String, String> params;
        private String text;
        /**
         * DO — something due now (a bill, a wallet check, a month to close, a set-aside);
         * IDEA — optional encouragement (start a goal, put spare money to work).
         */
        private String kind;
        /**
         * What the client's button does: SET_INCOME, PAY_SUBSCRIPTION, PAY_BANK, PAY_DEBT,
         * CLOSE_MONTH, CHECK_IN, SET_ASIDE, ADD_GOAL.
         */
        private String action;
        /** PAY_SUBSCRIPTION: the subscription id. SET_ASIDE into a goal: the goal's id. */
        private Long refId;
        /** SET_ASIDE: DONATION | EMERGENCY | INVESTMENTS | SAVINGS (a goal, see refId). */
        private String bucket;
        private BigDecimal amount;
    }
}
