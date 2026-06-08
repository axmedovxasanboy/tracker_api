package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Contribute additional money to an existing investment / savings goal. */
@Getter @Setter
public class InvestmentContributeRequest {

    @NotNull @DecimalMin("0.01")
    private BigDecimal amount;

    /** Must match the investment's currency. */
    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate date;

    /** Optional — null = cash. */
    private Long cardId;

    /** Optional override for the mirrored transaction's category; null = auto-pick by sub-type. */
    private Long categoryId;

    private String description;
}
