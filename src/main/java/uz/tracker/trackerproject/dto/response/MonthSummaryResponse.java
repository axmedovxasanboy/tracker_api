package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

/**
 * The monthly-envelope summary: what you started with, earned, set aside, spent and have left for
 * one month — the core "how much I earn / how much I spent" view. All money values are in
 * {@link #currency}.
 *
 * <h2>One bucket, one figure — open or closed</h2>
 * A bucket's total is <em>recorded payments + "already paid" marks</em> on every screen: the Plan,
 * Home, the bucket-history tabs and this page, whether the month is open or closed. Closing a
 * month must not change a number the user is looking at, so the closed branch replays the frozen
 * snapshot for the recorded half and adds the same marks the plan adds. That is safe because a
 * mark is as immutable as the snapshot: {@code FinanceService.markPaid} and
 * {@code FinanceService.deleteMark} both go through {@code MonthCloseService.assertMonthOpen}, so
 * once a month is closed its marks can neither be created nor removed. No stored row is rewritten.
 *
 * <h2>Two totals, because a mark is not wallet movement</h2>
 * <ul>
 *   <li>{@link #taggedTotal} — the "Set aside" headline, and exactly the sum of the five bucket
 *       figures above it. Counts marks.</li>
 *   <li>{@link #taggedRecorded} — the part of {@link #totalSpent} that went to a named bucket.
 *       Recorded only, because a mark moves no tracked money.</li>
 * </ul>
 * Both identities hold in both branches:
 * <pre>
 *   taggedTotal    = donation + emergency + investments + stocks + savings
 *   taggedTotal    = taggedRecorded + markedNotMoved
 *   everydaySpend  = totalSpent − taggedRecorded          (closed months only)
 * </pre>
 * {@link #taggedRecorded} is what the envelope arithmetic uses, and is the figure a close freezes
 * — folding marks into it would shrink {@code everydaySpend} by money that never left a wallet,
 * permanently, since a closed month can never be reopened.
 *
 * <p>{@link #everydaySpend}, {@link #totalSpent} and {@link #leftover} are null until the month is
 * closed: they are only known once the user enters their real end-of-month balances.
 */
@Getter @Builder
public class MonthSummaryResponse {

    private String month;
    private Currency currency;
    private boolean closed;

    private BigDecimal startBalance;   // carried in from the previous month
    private BigDecimal income;         // earned this month
    private BigDecimal donation;
    private BigDecimal emergency;
    private BigDecimal investments;
    private BigDecimal stocks;
    private BigDecimal savings;
    /** The "Set aside" headline: donation+emergency+investments+stocks+savings, marks included. */
    private BigDecimal taggedTotal;
    /**
     * The recorded-only half of {@link #taggedTotal} — real wallet movement into the buckets, and
     * the only bucket figure the envelope arithmetic may use. For a closed month this is the frozen
     * snapshot sum, so {@code everydaySpend = totalSpent − taggedRecorded} holds exactly.
     */
    private BigDecimal taggedRecorded;

    // ── "Already paid" marks: declared, but no money moved ─────────────────────
    private BigDecimal markedDonation;
    private BigDecimal markedEmergency;
    private BigDecimal markedInvestments;
    /**
     * Σ of every BUCKET mark for this month (donation + emergency + investments + stocks) — the
     * share of {@link #taggedTotal} that never left a wallet. Deliberately wider than the three
     * named marked* fields, which have no Stocks counterpart.
     */
    private BigDecimal markedNotMoved;

    /** Null until the month is closed. = totalSpent − taggedRecorded. */
    private BigDecimal everydaySpend;
    /** Null until the month is closed. = startBalance + income − leftover. */
    private BigDecimal totalSpent;
    /** Null until the month is closed. The real balance entered at close (next month's start). */
    private BigDecimal leftover;

}
