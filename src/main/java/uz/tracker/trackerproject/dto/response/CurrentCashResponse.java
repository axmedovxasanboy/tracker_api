package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The cash pot set to what the owner holds: what the app had, what it has now, and the difference booked. */
@Getter @Builder
public class CurrentCashResponse {
    private Currency currency;
    private LocalDate date;
    /** The cash balance the app computed before. */
    private BigDecimal previousBalance;
    /** The cash balance now — what the owner entered. */
    private BigDecimal balance;
    /** balance − previousBalance: negative = money gone (an everyday-spending expense), positive = found (income). */
    private BigDecimal adjustment;
    /** The EVERYDAY_SPENDING transaction booked for the difference; null when there was none. */
    private Long adjustmentTxId;
}
