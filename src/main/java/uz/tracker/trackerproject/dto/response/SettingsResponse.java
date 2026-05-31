package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Builder
public class SettingsResponse {

    private Long id;
    private BigDecimal monthlyStableIncome;
    private Currency monthlyStableIncomeCurrency;
    private BigDecimal usdToUzs;
    private BigDecimal eurToUzs;
    private java.time.LocalDate allocationTrackingStartMonth;
    private String telegramWebhookUrl;
    private String telegramWebViewUrl;
    private LocalDateTime updatedAt;

    public static SettingsResponse from(Settings s) {
        return SettingsResponse.builder()
                .id(s.getId())
                .monthlyStableIncome(s.getMonthlyStableIncome())
                .monthlyStableIncomeCurrency(s.getMonthlyStableIncomeCurrency())
                .usdToUzs(s.getUsdToUzs())
                .eurToUzs(s.getEurToUzs())
                .allocationTrackingStartMonth(s.getAllocationTrackingStartMonth())
                .telegramWebhookUrl(s.getTelegramWebhookUrl())
                .telegramWebViewUrl(s.getTelegramWebViewUrl())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
