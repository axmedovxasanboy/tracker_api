package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cross-month allocation ledger for the Overview page. Treats recommended-vs-paid as one
 * running balance from the configured start month to the selected month: overpaying later
 * clears earlier backlog. Every figure is broken down (left balance × %) so the user can
 * see exactly where it came from. All money fields are UZS.
 */
@Getter @Builder
public class AllocationLedgerResponse {

    private Currency currency;
    private String startMonth;       // YYYY-MM the ledger starts from (Settings, default = current)
    private String selectedMonth;    // YYYY-MM being viewed
    private boolean missingStableIncome;
    private boolean beforeTrackingStart; // viewed month precedes the configured tracking start
    private String trackingStartMonth;   // YYYY-MM the ledger will start from (null when unset)
    /**
     * True when the viewed month still has unpaid mandatory subscriptions. The tier withholds
     * its whole allocation in that state, so the ledger withholds its dues too — otherwise the
     * header prints a concrete monthly ask directly above "guidance unavailable".
     */
    private boolean subscriptionsPending;

    private BigDecimal stableIncome;     // selected month
    private BigDecimal bonusThisMonth;   // bonus-tagged income received in the selected month (display-only)
    private BigDecimal allocationBase;   // "left balance" = stable income − subscriptions − debt charge; the %s apply to it
    private Integer level;               // selected month (level is stable across months)
    private String subLevel;             // selected month

    // ── Headline totals ──────────────────────────────────────────────────────────
    /** Σ of each bucket's recommended for the selected month (% × left balance). */
    private BigDecimal dueThisMonth;
    /** Σ of each bucket's positive carried balance from months before the selected one. */
    private BigDecimal carriedFromPrevious;
    /** Σ of each bucket's outstanding balance through the selected month (net of all payments). */
    private BigDecimal totalDueNow;
    /** Range of prior months that contributed a shortfall (nullable when none). */
    private String carriedStartMonth;
    private String carriedEndMonth;

    private List<BucketLedger> buckets;
    private List<MonthBreakdown> months;

    @Getter @Builder
    public static class BucketLedger {
        private String bucket;            // DONATION / EMERGENCY / INVESTMENTS / STOCKS
        private String label;
        private BigDecimal percent;       // selected month % (null = not recommended this month)
        private BigDecimal recommended;   // selected month target (stable + bonus) × %
        private BigDecimal paid;          // paid in the selected month (recorded + marks)
        /** Portion of {@link #paid} that came from "already paid" marks — no money moved. */
        private BigDecimal marked;
        private BigDecimal carried;       // net balance from previous months (negative = ahead)
        private BigDecimal outstanding;   // max(0, running balance through selected month)
        private BigDecimal effectivePercent; // paid ÷ left balance this month, as a % (null when nothing paid)
        private boolean overAllocated;    // paid more than recommended this month
    }

    @Getter @Builder
    public static class MonthBreakdown {
        private String month;             // YYYY-MM
        private Integer level;
        private String subLevel;
        private BigDecimal stableIncome;
        private BigDecimal bonus;         // display-only
        private BigDecimal allocationBase; // "left balance" the %s apply to, with THAT month's debt charge
        private boolean selected;         // true for the month being viewed
        private List<MonthBucketLine> lines;
    }

    @Getter @Builder
    public static class MonthBucketLine {
        private String bucket;
        private BigDecimal percent;       // null = not recommended that month
        private BigDecimal recommended;   // % × that month's left balance
        private BigDecimal paid;
        private BigDecimal net;           // recommended − paid (positive = fell behind)
    }
}
