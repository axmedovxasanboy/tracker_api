package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Update an investment's current/market value (platform growth) without a transaction. */
@Getter @Setter
public class InvestmentValueRequest {

    @NotNull @DecimalMin("0")
    private BigDecimal currentValue;
}
