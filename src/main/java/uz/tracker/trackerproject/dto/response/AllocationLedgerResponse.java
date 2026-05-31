package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cross-month allocation ledger for the Overview page. Treats recommended-vs-paid as one
 * running balance from the configured start month to the selected month: overpaying later
 * clears earlier backlog. Every figure is broken down (income × level × %) so the user can
 * see exactly where it came from. All money fields are in the requested display currency.
 */
@Getter @Builder
public class AllocationLedgerResponse {

    private Currency currency;
    private String startMonth;       // YYYY-MM the ledger starts from (Settings, default = current)
    private String selectedMonth;    // YYYY-MM being viewed
    private boolean missingStableIncome;

    private BigDecimal stableIncome;     // selected month, display currency
    private BigDecimal bonusThisMonth;   // bonus-tagged income received in the selected month
    private Integer level;               // selected month (level is stable across months)
    private String subLevel;             // selected month

    // ── Headline totals (display currency) ──────────────────────────────────────
    /** Σ of each bucket's recommended for the selected month (stable + bonus based). */
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
        private BigDecimal paid;          // paid in the selected month
        private BigDecimal carried;       // net balance from previous months (negative = ahead)
        private BigDecimal outstanding;   // max(0, running balance through selected month)
        private BigDecimal effectivePercent; // paid ÷ (stable + bonus) this month, as a % (null when nothing paid)
        private boolean overAllocated;    // paid more than recommended this month
    }

    @Getter @Builder
    public static class MonthBreakdown {
        private String month;             // YYYY-MM
        private Integer level;
        private String subLevel;
        private BigDecimal stableIncome;  // display currency
        private BigDecimal bonus;         // display currency
        private boolean selected;         // true for the month being viewed
        private List<MonthBucketLine> lines;
    }

    @Getter @Builder
    public static class MonthBucketLine {
        private String bucket;
        private BigDecimal percent;       // null = not recommended that month
        private BigDecimal recommended;   // % × (stable + bonus), display currency
        private BigDecimal paid;          // display currency
        private BigDecimal net;           // recommended − paid (positive = fell behind)
    }
}
