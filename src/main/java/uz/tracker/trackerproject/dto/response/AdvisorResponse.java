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
        private BigDecimal target;
        private BigDecimal paid;
        /** What is still to set aside this month. */
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
        /** GOAL: the monthly payment — never more than what finishes the goal. */
        private BigDecimal target;
        /** GOAL: contributions to it this month, dated today or earlier. */
        private BigDecimal paid;
        /** max(0, target − paid); zero once the bucket is met. */
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
    }

    /** net = have + comingIn − goingOut − savings, over {@code days} days. */
    @Getter @Builder
    public static class Breakdown {
        private BigDecimal have;
        private BigDecimal comingIn;
        private BigDecimal goingOut;
        private BigDecimal savings;
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
