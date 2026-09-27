package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

/** "The cash I hold now": the cash pot's real balance, one currency. */
@Getter @Setter
public class CurrentCashRequest {

    @NotNull(message = "Currency is required")
    private Currency currency;

    /** What the owner counts in hand. */
    @NotNull(message = "Amount is required") @DecimalMin("0")
    private BigDecimal amount;

    /** The owner's local date; today (the server's) when omitted. */
    private LocalDate date;
}
