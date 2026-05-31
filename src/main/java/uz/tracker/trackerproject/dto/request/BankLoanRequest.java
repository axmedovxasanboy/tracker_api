package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter
public class BankLoanRequest {

    @NotBlank(message = "Bank name is required")
    private String bankName;

    @NotBlank(message = "Loan name is required")
    private String loanName;

    @NotNull @DecimalMin("0.01")
    private BigDecimal totalAmount;

    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate takenDate;

    private LocalDate endDate;

    /** Average monthly installment. Optional but recommended — drives the tier dashboard. */
    @DecimalMin(value = "0.0", message = "Monthly payment cannot be negative")
    private BigDecimal monthlyPayment;
}
