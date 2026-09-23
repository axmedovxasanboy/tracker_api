package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Whether a wallet check-in can be recorded on a given day, and what the owner needs to know
 * either way. Both clients render this; the rules live on the server so they cannot disagree.
 */
@Getter @Builder
public class WalletCheckInStatusResponse {

    private LocalDate date;
    /** YYYY-MM of {@link #date}. */
    private String month;

    /** True when a check-in may be recorded on {@link #date}. */
    private boolean allowed;
    /**
     * Why not, when {@link #allowed} is false, as a code a client can translate:
     * {@code MONTH_CLOSED} — this month is closed and locked. ({@code MONTH_ENDING}, which refused
     * a month's last days, is no longer sent: a check-in is allowed on any day of an open month.)
     */
    private String blockedCode;
    /** The same, as an English sentence — the fallback for a client with no translation. */
    private String blockedReason;

    /** Days from {@link #date} to the last day of the month; 0 on the last day itself. */
    private int daysUntilMonthEnd;
    /** The first day the month can be closed after it has ended — the 1st of next month. */
    private LocalDate nextMonthStart;

    /** The last day the wallets were reconciled: a check-in, or the end of the last closed month. */
    private LocalDate lastReconciledOn;
    /** Days since {@link #lastReconciledOn}; null when the wallets have never been reconciled. */
    private Integer daysSinceLastReconciled;
    /** How often a check-in is suggested, in days. */
    private int intervalDays;
    /** True when a check-in is allowed and at least {@link #intervalDays} days have passed. */
    private boolean due;
    /** When the next check-in is due — possibly in next month; null only when one is not {@link #allowed}. */
    private LocalDate nextDueOn;

    /** Net everyday spending recorded in this month so far — in an open month, the check-ins'. */
    private BigDecimal everydaySoFar;
    private long checkInsThisMonth;

    /** Every wallet with the balance the app computes for it as of {@link #date}. */
    private List<MonthClosePreviewResponse.WalletLine> wallets;
}
