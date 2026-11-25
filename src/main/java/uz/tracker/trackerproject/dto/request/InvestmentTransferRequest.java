package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Move money from one plain investment to another (Investments page, Batch 78). */
@Getter @Setter
public class InvestmentTransferRequest {

    @NotNull(message = "Choose where the money comes from.")
    private Long fromId;

    @NotNull(message = "Choose where the money goes.")
    private Long toId;

    /** No more than the from-holding's value (currentValue, else investedAmount). */
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    private BigDecimal amount;

    /** Not after today (Asia/Tashkent). */
    @NotNull(message = "Date is required")
    private LocalDate date;

    private String note;
}
