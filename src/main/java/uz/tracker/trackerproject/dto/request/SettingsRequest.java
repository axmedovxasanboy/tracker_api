package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

@Getter @Setter
public class SettingsRequest {

    @DecimalMin(value = "0.0", message = "Monthly stable income cannot be negative")
    private BigDecimal monthlyStableIncome;

    private Currency monthlyStableIncomeCurrency;

    @DecimalMin(value = "0.0", message = "USD→UZS rate cannot be negative")
    private BigDecimal usdToUzs;

    @DecimalMin(value = "0.0", message = "EUR→UZS rate cannot be negative")
    private BigDecimal eurToUzs;

    /** Month (any day; normalised to the 1st) the allocation ledger should start from. */
    private java.time.LocalDate allocationTrackingStartMonth;

    /**
     * Telegram bot developer settings. Sent as strings; an empty string clears the value.
     * A {@code null} (field omitted) leaves the stored value unchanged.
     */
    @Size(max = 512, message = "Webhook URL is too long")
    private String telegramWebhookUrl;

    @Size(max = 512, message = "Web-view URL is too long")
    private String telegramWebViewUrl;
}
