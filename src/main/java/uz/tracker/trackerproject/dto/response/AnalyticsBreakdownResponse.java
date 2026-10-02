package uz.tracker.trackerproject.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Flow;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionFlow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the six Analytics V2 pages show, for a range of months (ANALYTICS-V2-SPEC §4.2–§4.3):
 * the flow month by month, what each figure is expected to be, and the lines behind In, Out, Set
 * aside and what moved. Read-only; the browser classifies nothing — every row is placed by
 * {@code TransactionFlows.of} and counted in {@code AnalyticsService.monthOf}'s month. Months are
 * 'YYYY-MM', days 'YYYY-MM-DD', amounts UZS.
 */
@Getter @Builder
public class AnalyticsBreakdownResponse {

    private Currency currency;
    /** The owner's local day the figures are as of. */
    private LocalDate date;
    /** The range after its defaults, not clipped to {@link History#getStart()}. */
    private String from;
    private String to;
    private History history;
    /** Rows counted inside the range's months but dated after {@link #date}: not counted yet. */
    private int notYetCount;
    /** Settings' monthly income for {@link #to}; null when unset. Context only, never a base. */
    private BigDecimal stableIncome;
    /** One per month from max(from, history.start) to {@code to}; [] when nothing is recorded. */
    private List<MonthFlow> months;
    /** Σ {@link #months}. */
    private Flow total;
    /** Null when {@code history.start} is null. */
    private SinceStart sinceStart;
    /** One-month range with at least one base month; else null. */
    private Expected expected;
    /** Multi-month range with at least one complete tracked month; else null. */
    private Average average;
    /** Rows dated inside the range but counted in a month outside it (a salary marked for another month). */
    private List<Received> receivedForOtherMonths;
    /** One-month range: day 1 … min(date, month end), itemised everyday spending only; else []. */
    private List<DayEveryday> everydayDaily;
    private List<Line> income;
    private List<Line> out;
    private List<Line> setAside;
    private List<Line> moved;
    /** Savings goals, plans by deadline then wishes. */
    private List<Goal> goals;
    /** Every UZS holding at its value now, largest first (+ emergency money kept in no holding). */
    private List<Holding> holdingsNow;

    @Getter @Builder
    public static class History {
        /** The latest of trackingStart, firstEarned, firstOut (§1.2); null when no row counts at all. */
        private String start;
        private String firstEarned;
        private String firstOut;
        private String trackingStart;
        /** Months from {@link #start} to the month of {@code date} with at least one counted row. */
        private List<String> tracked;
    }

    /** A month's flow, as {@code GET /analytics}' months[] with whether the month is tracked and its length. */
    @Getter
    public static class MonthFlow extends Flow {
        /** YYYY-MM. */
        private final String month;
        /** The month ended before {@code date}. */
        private final boolean complete;
        /** At least one counted row; an untracked month is left out of every average. */
        private final boolean tracked;
        /** The whole month when complete, else the day-of-month of {@code date}. */
        private final int days;
        private final int daysInMonth;

        public MonthFlow(String month, boolean complete, boolean tracked, int days, int daysInMonth) {
            this.month = month;
            this.complete = complete;
            this.tracked = tracked;
            this.days = days;
            this.daysInMonth = daysInMonth;
        }
    }

    /**
     * A Flow worked out from other months — an expected or an average month — so it has no wallets:
     * {@code walletChange} and {@code payForOtherMonths} are null and {@code count} is 0. Out and Left
     * over are worked out from the parts as they are given, already rounded, so the identities hold.
     */
    public static class Estimate extends Flow {
        public Estimate(BigDecimal earned, BigDecimal earnedPay, BigDecimal earnedBonus, BigDecimal earnedOther,
                        BigDecimal everyday, BigDecimal everydayUnitemised, BigDecimal bills, BigDecimal loanPayments,
                        BigDecimal saved, BigDecimal savedDonation, BigDecimal savedEmergency,
                        BigDecimal savedInvestments, BigDecimal savedGoals, BigDecimal borrowed, BigDecimal lent,
                        BigDecimal returned, BigDecimal fromSavings) {
            this.earned = earned;
            this.earnedPay = earnedPay;
            this.earnedBonus = earnedBonus;
            this.earnedOther = earnedOther;
            this.everyday = everyday;
            this.everydayUnitemised = everydayUnitemised;
            this.bills = bills;
            this.loanPayments = loanPayments;
            this.saved = saved;
            this.savedDonation = savedDonation;
            this.savedEmergency = savedEmergency;
            this.savedInvestments = savedInvestments;
            this.savedGoals = savedGoals;
            this.borrowed = borrowed;
            this.lent = lent;
            this.returned = returned;
            this.fromSavings = fromSavings;
            this.out = everyday.add(bills).add(loanPayments);
            this.leftOver = earned.subtract(out).subtract(saved);
            this.payForOtherMonths = null;
            this.walletChange = null;
        }
    }

    @Getter @Builder
    public static class SinceStart {
        /** = history.start. */
        private String from;
        /** Set aside from {@code from} to {@code date}, gross — money taken back out is not subtracted. */
        private BigDecimal setAside;
        /** FROM_SAVINGS from {@code from} to {@code date}. */
        private BigDecimal fromSavings;
    }

    @Getter @Builder
    public static class Expected {
        private String month;
        /** The base months, oldest first: tracked months before {@link #month} that ended before {@code date}. */
        private List<String> basedOn;
        /** Months between history.start and {@link #month} left out as untracked. */
        private List<String> skipped;
        /** daysIn(month) ÷ the base months' mean length, 4 dp. Display only — never a multiplier. */
        private BigDecimal dayFactor;
        private Flow flow;
        /** Month in progress only: the itemised everyday expected by {@code date}; else null. */
        private BigDecimal everydayByToday;
        /** Σ expected of the loans already paid off ({@code closed}): not "to come". */
        private BigDecimal paidOffLoans;
    }

    @Getter @Builder
    public static class Average {
        /** The complete tracked months of the range, oldest first. */
        private List<String> basedOn;
        private Flow flow;
    }

    @Getter @Builder
    public static class Received {
        private LocalDate date;
        /** YYYY-MM the row counts in. */
        private String countedIn;
        private BigDecimal amount;
        private Long categoryId;
        private String name;
        private String nameUz;
    }

    @Getter @Builder
    public static class DayEveryday {
        private int day;
        private BigDecimal amount;
        private BigDecimal cumulative;
    }

    /** One line of income[], out[], setAside[] or moved[] (§4.3 "Line"). */
    @Getter @Builder
    public static class Line {
        /** Unique within its list and stable across requests: cat:{id}, bill:{id}, loans, group:GOALS, … */
        private String key;
        /** CATEGORY · BILL · LOAN · NOT_ITEMISED · CHECK · GROUP · HOLDING · GOAL · DONATION_KIND · PERSON. */
        private String kind;
        private Long refId;
        /** The thing's own name; a GROUP's is a code the web translates. */
        private String name;
        private String nameUz;
        /** income[] lines: PAY · BONUS · OTHER; null for a root whose children differ. */
        private String incomeKind;
        /** LOAN lines: BANK · LOAN · DEBT. */
        private String loanKind;
        /** LOAN lines of a LOAN or DEBT: paid off now, its last repayment before the line's month. */
        private boolean closed;
        /** moved[] groups. */
        private TransactionFlow flow;
        private BigDecimal amount;
        /** out[] CATEGORY lines only: the everyday and the bills part of {@link #amount}. */
        private BigDecimal everyday;
        private BigDecimal bills;
        private int count;
        /** One-month range with a base: §1.2, rounded once; null when new, for moved[] and for a range. */
        private BigDecimal expected;
        /** Wrapped so the property is named "isNew" (a primitive would be read as "new"). */
        private Boolean isNew;
        /** Multi-month range: Σ complete tracked months ÷ their count; null for moved[] lines. */
        private BigDecimal average;
        /** Multi-month range: the amount per months[] entry, same order; else []. */
        private List<BigDecimal> byMonth;
        /** setAside[] group lines only: Σ from history.start to {@code date}. */
        private BigDecimal sinceStart;
        /** income[] only: rows counted here whose day is in another month. */
        private List<OtherMonth> otherMonth;
        /** out[] CATEGORY lines only: the 3 largest itemised everyday rows. */
        private List<TopRow> top;
        /** The History filter that lists exactly this line's rows for the month; null when none can. */
        private LineHistory history;
        private List<Line> children;
    }

    @Getter @Builder
    public static class OtherMonth {
        private LocalDate date;
        private BigDecimal amount;
    }

    @Getter @Builder
    public static class TopRow {
        private Long id;
        private LocalDate date;
        private String description;
        private BigDecimal amount;
    }

    /** History's query parameters for one line; only the ones set are sent. */
    @Getter @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LineHistory {
        private Long categoryId;
        private Long investmentId;
        /** A TransactionFlow, or a comma list of them. */
        private String flow;
        private Boolean walletCheck;
        private LocalDate from;
        private LocalDate to;
    }

    /**
     * A savings goal (an Investment with {@code savingsGoal}) — or a holding that is no goal now but
     * took goal money up to {@code to}, listed as a WISH so Goals' put-in stays Set aside's Goals group.
     */
    @Getter @Builder
    public static class Goal {
        private Long refId;
        private String name;
        /** PLAN · WISH. */
        private String kind;
        private BigDecimal target;
        /** YYYY-MM. */
        private String deadline;
        /** A plan's monthly payment; null for a wish. */
        private BigDecimal monthly;
        private String startMonth;
        private String createdMonth;
        private BigDecimal valueNow;
        private boolean valueTracked;
        /** All-time rows linked to the goal whose savings kind is GOAL — the Set aside page's Goals group. */
        private BigDecimal putInTotal;
        private BigDecimal takenOutTotal;
        /** The last month ≤ {@code to} with money put in. */
        private String lastPutIn;
        /** From max(history.start, createdMonth) to {@code to}, at most the last 24. */
        private List<GoalMonth> months;
    }

    @Getter @Builder
    public static class GoalMonth {
        private String month;
        private BigDecimal putIn;
        private BigDecimal takenOut;
        /** putIn − takenOut. Display only: no value is rebuilt from it. */
        private BigDecimal net;
        /** A plan's ask for the month, capped at what finishes the goal; null for a wish or before it starts. */
        private BigDecimal asked;
        /** What the goal had reached at the month's end (now, for the month in progress). */
        private BigDecimal reachedEnd;
        /** {@link #reachedEnd} could not be rebuilt exactly and comes from no CLOSING snapshot. */
        private boolean approximate;
        private boolean fromSnapshot;
        private LineHistory history;
    }

    @Getter @Builder
    public static class Holding {
        /** Null for the emergency money kept in no holding. */
        private Long refId;
        private String name;
        /** INVESTMENT · EMERGENCY · GOAL. */
        private String kind;
        private BigDecimal value;
        private BigDecimal putIn;
        private boolean valueTracked;
        private boolean openingBalance;
    }
}
