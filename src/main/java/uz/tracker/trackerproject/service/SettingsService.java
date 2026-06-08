package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.SettingsRequest;
import uz.tracker.trackerproject.dto.response.SettingsResponse;
import uz.tracker.trackerproject.dto.response.TelegramConfigResponse;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.repository.SettingsRepository;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SettingsRepository repository;

    @Transactional
    public SettingsResponse get() {
        return SettingsResponse.from(getOrCreate());
    }

    @Transactional
    public SettingsResponse update(SettingsRequest req) {
        Settings s = getOrCreate();
        if (req.getMonthlyStableIncome() != null) s.setMonthlyStableIncome(req.getMonthlyStableIncome());
        if (req.getMonthlyStableIncomeCurrency() != null) s.setMonthlyStableIncomeCurrency(req.getMonthlyStableIncomeCurrency());
        if (req.getUsdToUzs() != null) s.setUsdToUzs(req.getUsdToUzs());
        if (req.getEurToUzs() != null) s.setEurToUzs(req.getEurToUzs());
        if (req.getAllocationTrackingStartMonth() != null) {
            // Write-once: locked the moment it's first set. Re-sending the SAME value is a
            // no-op (so saving other settings still works); a DIFFERENT value is rejected.
            java.time.LocalDate incoming = req.getAllocationTrackingStartMonth().withDayOfMonth(1);
            java.time.LocalDate existing = s.getAllocationTrackingStartMonth();
            if (existing == null) {
                s.setAllocationTrackingStartMonth(incoming);
            } else if (!existing.equals(incoming)) {
                throw new IllegalArgumentException(
                        "Allocation tracking start month is locked once set and can't be changed.");
            }
        }
        // URLs: an explicit empty string clears the value; a null (omitted field) leaves it as-is.
        if (req.getTelegramWebhookUrl() != null) s.setTelegramWebhookUrl(blankToNull(req.getTelegramWebhookUrl()));
        if (req.getTelegramWebViewUrl() != null) s.setTelegramWebViewUrl(blankToNull(req.getTelegramWebViewUrl()));
        return SettingsResponse.from(repository.save(s));
    }

    /** Public, non-secret Telegram config consumed by the bot at startup. */
    @Transactional
    public TelegramConfigResponse getTelegramConfig() {
        return TelegramConfigResponse.from(getOrCreate());
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Convenience for OverviewService (next phase): always returns a Settings row. */
    @Transactional
    public Settings getOrCreate() {
        return repository.findById(Settings.SINGLETON_ID).orElseGet(() -> {
            Settings s = new Settings();
            s.setId(Settings.SINGLETON_ID);
            return repository.save(s);
        });
    }
}
