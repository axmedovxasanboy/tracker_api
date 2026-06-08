package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter
public class EmergencyRequest {

    @NotNull @DecimalMin("0.0001")
    private BigDecimal amount;

    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate date;

    private String description;

    /** Optional — null = cash. See DonationRequest.cardId for rationale. */
    private Long cardId;

    /** Optional override for the mirrored transaction's category; null = auto-pick by sub-type. */
    private Long categoryId;
}
