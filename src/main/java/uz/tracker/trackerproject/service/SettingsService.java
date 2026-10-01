package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.SettingsRequest;
import uz.tracker.trackerproject.dto.response.SettingsResponse;
import uz.tracker.trackerproject.dto.response.TelegramConfigResponse;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.StableIncomeEntry;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.SettingsRepository;
import uz.tracker.trackerproject.repository.StableIncomeEntryRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SettingsService {

    /** How far ahead a change of the monthly income may be recorded. */
    static final int STABLE_INCOME_MAX_MONTHS_AHEAD = 12;

    private final SettingsRepository repository;

    /**
     * The monthly income's history (STABLE-INCOME-HISTORY.md). Field-injected, so the constructor
     * every caller uses stays as it is; absent (null) where the service is built by hand, which then
     * behaves as before the history existed — one value for every month.
     */
    @Autowired(required = false)
    private StableIncomeEntryRepository stableIncomeEntries;

    /** For the earliest transaction month — the first month an income entry may start without a tracking start. */
    @Autowired(required = false)
    private TransactionRepository transactionRepository;

    @Transactional
    public SettingsResponse get() {
        return get(LocalDate.now());
    }

    /** @param date the owner's local day; its month is "the current month" */
    @Transactional
    public SettingsResponse get(LocalDate date) {
        Settings s = getOrCreate();
        syncCurrentIncome(s, YearMonth.from(date));
        return response(s, YearMonth.from(date));
    }

    @Transactional
    public SettingsResponse update(SettingsRequest req) {
        return update(req, LocalDate.now());
    }

    /**
     * @param date the owner's local day: without {@code stableIncomeFrom} a changed income applies from
     *             its month, and {@code stableIncomeFrom} may be at most 12 months after it
     */
    @Transactional
    public SettingsResponse update(SettingsRequest req, LocalDate date) {
        YearMonth current = YearMonth.from(date);
        Settings s = getOrCreate();
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
        YearMonth from = parseMonth("stableIncomeFrom", req.getStableIncomeFrom());
        if (req.getMonthlyStableIncome() != null) {
            applyStableIncome(s, req.getMonthlyStableIncome(), from, current);
        } else if (from != null) {
            throw new IllegalArgumentException("stableIncomeFrom needs monthlyStableIncome: the amount that applies from it.");
        }
        // URLs: an explicit empty string clears the value; a null (omitted field) leaves it as-is.
        if (req.getTelegramWebhookUrl() != null) s.setTelegramWebhookUrl(blankToNull(req.getTelegramWebhookUrl()));
        if (req.getTelegramWebViewUrl() != null) s.setTelegramWebViewUrl(blankToNull(req.getTelegramWebViewUrl()));
        syncCurrentIncome(s, current);
        return response(repository.save(s), current);
    }

    // ── The monthly income, month by month ─────────────────────────────────────

    /**
     * Record {@code amount} from {@code from} on (an existing entry for that month takes the new
     * amount; entries after it are kept). Without {@code from}: from the current month — never
     * retroactively — and an amount equal to the current month's changes nothing, so other settings
     * can be saved without touching the history. The very first amount (no history yet) applies from
     * the first month that can hold one, which reads the same for every month.
     */
    private void applyStableIncome(Settings s, BigDecimal amount, YearMonth from, YearMonth current) {
        if (stableIncomeEntries == null) {               // built by hand: the single value, as before
            s.setMonthlyStableIncome(amount);
            return;
        }
        YearMonth first = stableIncomeFirstMonth(s, current);
        YearMonth month;
        if (from != null) {
            if (from.isBefore(first)) {
                throw new IllegalArgumentException("The monthly income can change from " + first
                        + " at the earliest (the first month tracked), not " + from + ".");
            }
            YearMonth last = current.plusMonths(STABLE_INCOME_MAX_MONTHS_AHEAD);
            if (from.isAfter(last)) {
                throw new IllegalArgumentException("The monthly income can change up to " + last
                        + " (12 months ahead), not " + from + ".");
            }
            month = from;
        } else if (stableIncomeEntries.count() == 0) {
            month = first;
        } else {
            BigDecimal now = stableIncomeSchedule().amountFor(current);
            if (now != null && now.compareTo(amount) == 0) return;
            month = current;
        }
        StableIncomeEntry entry = stableIncomeEntries.findByMonth(month.atDay(1))
                .orElseGet(() -> new StableIncomeEntry(month.atDay(1), amount));
        entry.setAmount(amount);
        stableIncomeEntries.save(entry);
    }

    /**
     * Remove the entry for {@code month}, so that month falls back to the entry before it. The only
     * remaining entry cannot be removed — change the amount instead.
     */
    @Transactional
    public SettingsResponse deleteStableIncome(String month, LocalDate date) {
        YearMonth m = parseMonth("month", month);
        if (m == null) throw new IllegalArgumentException("month must be YYYY-MM, got: " + month);
        if (stableIncomeEntries == null) throw new ResourceNotFoundException("No monthly income is recorded from " + m + ".");
        StableIncomeEntry entry = stableIncomeEntries.findByMonth(m.atDay(1))
                .orElseThrow(() -> new ResourceNotFoundException("No monthly income is recorded from " + m + "."));
        if (stableIncomeEntries.count() <= 1) {
            throw new IllegalArgumentException("This is the only monthly income recorded — change the amount instead.");
        }
        stableIncomeEntries.delete(entry);
        stableIncomeEntries.flush();
        Settings s = getOrCreate();
        syncCurrentIncome(s, YearMonth.from(date));
        return response(repository.save(s), YearMonth.from(date));
    }

    /** The monthly income month by month — the single source every month-scoped figure reads. */
    @Transactional(readOnly = true)
    public StableIncomeSchedule stableIncomeSchedule() {
        Settings s = getOrCreate();
        BigDecimal single = s == null ? null : s.getMonthlyStableIncome();
        if (stableIncomeEntries == null) return StableIncomeSchedule.single(single);
        return StableIncomeSchedule.of(stableIncomeEntries.findAllByOrderByMonthAsc(), single);
    }

    /** {@code month}'s monthly income; null when none is set. */
    @Transactional(readOnly = true)
    public BigDecimal stableIncomeFor(YearMonth month) {
        return stableIncomeSchedule().amountFor(month);
    }

    /**
     * The earliest month a change may apply from: the tracking start month, else the month of the
     * earliest transaction, else {@code current}.
     */
    YearMonth stableIncomeFirstMonth(Settings s, YearMonth current) {
        if (s != null && s.getAllocationTrackingStartMonth() != null) return YearMonth.from(s.getAllocationTrackingStartMonth());
        LocalDate earliest = transactionRepository == null ? null : transactionRepository.findEarliestTransactionDate();
        return earliest != null ? YearMonth.from(earliest) : current;
    }

    /**
     * Boot back-fill (DataSeeder): with no history yet and an income set, ONE entry with that amount
     * from the first month — so every month reads exactly what it read before. Idempotent: once the
     * history has any entry it does nothing. Returns whether it wrote the entry.
     */
    @Transactional
    public boolean backfillStableIncomeHistory() {
        if (stableIncomeEntries == null || stableIncomeEntries.count() > 0) return false;
        Settings s = getOrCreate();
        if (s.getMonthlyStableIncome() == null) return false;
        YearMonth first = stableIncomeFirstMonth(s, YearMonth.now());
        stableIncomeEntries.save(new StableIncomeEntry(first.atDay(1), s.getMonthlyStableIncome()));
        return true;
    }

    /**
     * Keep {@code Settings.monthlyStableIncome} equal to the current month's value, so every client
     * that reads it keeps working — a raise recorded ahead takes effect there when its month comes.
     */
    @Transactional
    public void syncCurrentIncome(YearMonth current) {
        Settings s = getOrCreate();
        syncCurrentIncome(s, current);
        repository.save(s);
    }

    private void syncCurrentIncome(Settings s, YearMonth current) {
        if (stableIncomeEntries == null || stableIncomeEntries.count() == 0) return;
        BigDecimal now = stableIncomeSchedule().amountFor(current);
        if (now != null && (s.getMonthlyStableIncome() == null || now.compareTo(s.getMonthlyStableIncome()) != 0)) {
            s.setMonthlyStableIncome(now);
        }
    }

    private SettingsResponse response(Settings s, YearMonth current) {
        List<SettingsResponse.StableIncomeLine> history = new ArrayList<>();
        if (stableIncomeEntries != null) {
            for (Map.Entry<YearMonth, BigDecimal> e : stableIncomeSchedule().entries().entrySet()) {
                history.add(SettingsResponse.StableIncomeLine.builder()
                        .month(e.getKey().toString()).amount(e.getValue()).build());
            }
        }
        return SettingsResponse.from(s, history, stableIncomeFirstMonth(s, current).toString());
    }

    private static YearMonth parseMonth(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be YYYY-MM, got: " + value);
        }
    }

    // ── Write guard (called by every money-writing service) ────────────────────

    /**
     * Reject any money-writing action until a monthly stable income is configured.
     * Everything downstream — the tier, the allocation base, every bucket recommendation —
     * is derived from it, so recording money before it is set produces figures that are
     * silently wrong rather than merely absent. "Configured" is the current month's value.
     *
     * Mirrors {@code MonthCloseService.assertMonthOpen}: called as the first statement of
     * each write path, throws {@link IllegalArgumentException} so the global handler turns
     * it into a 400 with a readable message.
     */
    public void assertStableIncomeSet() {
        if (!stableIncomeSchedule().isSet(YearMonth.now())) {
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
