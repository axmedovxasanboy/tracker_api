package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.CardType;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

@Getter @Setter
public class CardRequest {

    @NotBlank(message = "Card name is required")
    private String name;

    @NotBlank(message = "Bank name is required")
    private String bankName;

    @NotNull(message = "Card type is required")
    private CardType type;

    @NotBlank(message = "Last four digits are required")
    @Pattern(regexp = "\\d{4}", message = "Must be exactly 4 digits")
    private String lastFourDigits;

    // Optional — no @Size because empty string must be treated as absent
    private String fullNumber;

    // Optional — same reason
    private String pin;

    @NotNull(message = "Initial balance is required")
    private BigDecimal initialBalance;

    @NotNull(message = "Currency is required")
    private Currency currency;

    private String color;
}
