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

    /** When true, this investment is the emergency fund (counts toward the Emergency bucket). */
    private Boolean emergencyFund;

    /** When true, this investment is a savings goal (Savings area; excluded from the
        mandatory Investments bucket). */
    private Boolean savingsGoal;

    /** Optional savings-goal target. */
    @DecimalMin("0")
    private BigDecimal targetAmount;

    /** Optional current/market value (platform growth). Null = treated as investedAmount. */
    @DecimalMin("0")
    private BigDecimal currentValue;

    /** Optional — null = cash. See DonationRequest.cardId for rationale. */
    private Long cardId;

    /** Optional override for the mirrored transaction's category; null = auto-pick by sub-type. */
    private Long categoryId;
}
