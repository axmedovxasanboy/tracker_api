package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

@Getter @Setter
public class CashBalanceRequest {

    @NotNull(message = "Currency is required")
    private Currency currency;

    /** Allowed to be negative if the user is overdrawn in cash for some reason. */
    @NotNull(message = "Initial balance is required")
    private BigDecimal initialBalance;
}
