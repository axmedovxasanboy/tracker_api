package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter
public class InvestmentRequest {

    @NotBlank
    private String name;

    @NotNull
    private InvestmentType type;

    @NotNull @DecimalMin("0.01")
    private BigDecimal investedAmount;

    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate purchaseDate;

    private String broker;

    private String description;

    /** Optional — null = cash. See DonationRequest.cardId for rationale. */
    private Long cardId;
}
