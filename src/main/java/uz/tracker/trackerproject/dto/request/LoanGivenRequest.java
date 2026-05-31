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
public class LoanGivenRequest {

    @NotBlank
    private String debtorName;

    @NotNull @DecimalMin("0.01")
    private BigDecimal totalAmount;

    private BigDecimal receivedAmount;

    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate lentDate;

    private LocalDate expectedReturnDate;

    private RecordStatus status;

    private String description;
}
