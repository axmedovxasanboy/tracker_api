package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter
public class MonthlyPaymentPayRequest {

    public enum Mode { CASH, CARD, BOTH }

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.0001", message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotNull(message = "Payment date is required")
    private LocalDate paymentDate;

    @NotNull(message = "Payment mode is required")
    private Mode mode;

    /** Required when mode = CARD or BOTH. */
    private Long cardId;

    /** Required when mode = BOTH; portion paid in cash. Must be in (0, amount). */
    private BigDecimal cashAmount;

    /** If true, the MonthlyPayment.amount default is updated to this payment's amount. */
    private Boolean updateAmountForFuture;
}
