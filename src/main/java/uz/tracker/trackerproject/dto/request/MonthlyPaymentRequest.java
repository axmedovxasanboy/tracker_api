package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter
public class MonthlyPaymentRequest {

    @NotBlank
    private String name;

    @NotNull @DecimalMin("0.01")
    private BigDecimal amount;

    @NotNull
    private Currency currency;

    @NotNull @Min(1) @Max(31)
    private Integer dueDay;

    private Boolean active;

    private String description;

    private LocalDate nextDueDate;

    private LocalDate subscribedSince;

    private Long categoryId;
}
