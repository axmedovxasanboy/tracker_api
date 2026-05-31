package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter
public class DebtRequest {

    @NotBlank(message = "Creditor name is required")
    private String creditorName;

    @NotNull @DecimalMin("0.01")
    private BigDecimal totalAmount;

    private BigDecimal paidAmount;

    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate borrowedDate;

    private LocalDate dueDate;

    /**
     * Month (any day; normalised to the 1st server-side) from which repayments start
     * counting toward the Overview tier. Optional — defaults to the month after
     * borrowedDate when omitted.
     */
    private LocalDate paymentStartDate;

    private RecordStatus status;

    private String description;
}
