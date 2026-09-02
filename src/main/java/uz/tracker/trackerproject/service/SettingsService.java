package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.SettingsRequest;
import uz.tracker.trackerproject.dto.response.SettingsResponse;
import uz.tracker.trackerproject.dto.response.TelegramConfigResponse;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
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
        // UZS-only: the field is kept for a future multi-currency rework, but it is never
        // chosen by the user and must never be left null (nothing may gate on it).
        s.setMonthlyStableIncomeCurrency(Currency.UZS);
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


    // ── Write guard (called by every money-writing service) ────────────────────

    /**
     * Reject any money-writing action until a monthly stable income is configured.
     * Everything downstream — the tier, the allocation base, every bucket recommendation —
     * is derived from it, so recording money before it is set produces figures that are
     * silently wrong rather than merely absent.
     *
     * Mirrors {@code MonthCloseService.assertMonthOpen}: called as the first statement of
     * each write path, throws {@link IllegalArgumentException} so the global handler turns
     * it into a 400 with a readable message.
     */
    public void assertStableIncomeSet() {
        Settings s = getOrCreate();
        if (s.getMonthlyStableIncome() == null || s.getMonthlyStableIncome().signum() <= 0) {
            throw new IllegalArgumentException(
                    "Set your monthly stable income in Settings before recording any money. "
                    + "Your tier and every allocation figure are calculated from it.");
        }
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
