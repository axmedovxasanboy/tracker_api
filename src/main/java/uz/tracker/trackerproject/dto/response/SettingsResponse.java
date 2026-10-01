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
    private java.time.LocalDate allocationTrackingStartMonth;
    /** The monthly income from each month it was set for, oldest first. */
    private java.util.List<StableIncomeLine> stableIncomeHistory;
    /** 'YYYY-MM': the earliest month {@code stableIncomeFrom} may take. */
    private String stableIncomeFirstMonth;
    private String telegramWebhookUrl;
    private String telegramWebViewUrl;
    private LocalDateTime updatedAt;

    /** One entry of the monthly income's history: {@link #amount} from {@link #month} on. */
    @Getter @Builder
    public static class StableIncomeLine {
        /** YYYY-MM. */
        private String month;
        private BigDecimal amount;
    }

    public static SettingsResponse from(Settings s, java.util.List<StableIncomeLine> history, String firstMonth) {
        return base(s).stableIncomeHistory(history).stableIncomeFirstMonth(firstMonth).build();
    }

    public static SettingsResponse from(Settings s) {
        return base(s).build();
    }

    private static SettingsResponseBuilder base(Settings s) {
        return SettingsResponse.builder()
                .id(s.getId())
                .monthlyStableIncome(s.getMonthlyStableIncome())
                .monthlyStableIncomeCurrency(s.getMonthlyStableIncomeCurrency())
                .allocationTrackingStartMonth(s.getAllocationTrackingStartMonth())
                .telegramWebhookUrl(s.getTelegramWebhookUrl())
                .telegramWebViewUrl(s.getTelegramWebViewUrl())
                .updatedAt(s.getUpdatedAt());
    }
}
