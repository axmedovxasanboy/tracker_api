package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Take money out of an investment or savings goal into a wallet — all of it or a part. */
@Getter @Setter
public class InvestmentWithdrawRequest {

    /** No more than the holding's value (currentValue, else investedAmount). */
    @NotNull @DecimalMin("0.01")
    private BigDecimal amount;

    /** Must match the investment's currency. */
    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate date;

    /** The wallet the money goes into: a card in the same currency; null = cash. */
    private Long cardId;

    /** Optional; "Taken from {name}" when blank. */
    private String description;
}
