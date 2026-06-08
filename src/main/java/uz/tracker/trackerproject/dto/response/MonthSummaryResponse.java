package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

/**
 * The monthly-envelope summary: what you started with, earned, tagged, spent and have left for
 * one month — the core "how much I earn / how much I spent" view. For a CLOSED month every
 * figure comes from the immutable snapshot; for an OPEN month the everyday-spend / total-spent /
 * leftover figures are null because they're only known once you close the month and enter your
 * real balances. All money values are in {@link #currency}.
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
    private BigDecimal taggedTotal;    // donation+emergency+investments+stocks+savings

    /** Null until the month is closed. */
    private BigDecimal everydaySpend;
    /** Null until the month is closed. = startBalance + income − leftover. */
    private BigDecimal totalSpent;
    /** Null until the month is closed. The real balance entered at close (next month's start). */
    private BigDecimal leftover;

    private boolean fxRatesUsingDefaults;
}
