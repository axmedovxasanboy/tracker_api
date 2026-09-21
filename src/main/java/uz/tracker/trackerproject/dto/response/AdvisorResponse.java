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
     * One piece of advice. {@code code} is a translation key the client renders with
     * {@code params}; {@code text} is the same sentence in English for a client without it.
     * Amounts are never inside {@code params} — {@link #amount} carries the number and each client
     * formats it its own way.
     */
    @Getter @Builder
    public static class Suggestion {
        private String code;
        private Map<String, String> params;
        private String text;
        /**
         * DO — something due now (a bill, a wallet check, a month to close, a set-aside);
         * IDEA — optional encouragement (start a goal, put spare money to work);
         * WARN — a heads-up with nothing to tap.
         */
        private String kind;
        /**
         * What the client's button does: SET_INCOME, PAY_SUBSCRIPTION, PAY_BANK, PAY_DEBT,
         * CLOSE_MONTH, CHECK_IN, SET_ASIDE, ADD_GOAL — or null for WARN.
         */
        private String action;
        /** PAY_SUBSCRIPTION: the subscription id. SET_ASIDE into a goal: the goal's id. */
        private Long refId;
        /** SET_ASIDE: DONATION | EMERGENCY | INVESTMENTS | SAVINGS (a goal, see refId). */
        private String bucket;
        private BigDecimal amount;
    }
}
