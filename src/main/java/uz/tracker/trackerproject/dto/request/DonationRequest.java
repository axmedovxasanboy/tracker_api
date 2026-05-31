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
public class DonationRequest {

    @NotBlank
    private String recipientName;

    @NotNull @DecimalMin("0.01")
    private BigDecimal amount;

    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate donationDate;

    private String description;

    private Boolean anonymous;

    /**
     * Optional — when set, the auto-created Transaction is booked against this card.
     * Null = cash. Used when the donation is recorded directly (not via the
     * Transaction modal, which already passes the card on its side).
     */
    private Long cardId;
}
