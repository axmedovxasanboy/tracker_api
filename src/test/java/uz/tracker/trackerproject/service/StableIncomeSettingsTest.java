package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uz.tracker.trackerproject.dto.request.SettingsRequest;
import uz.tracker.trackerproject.dto.response.SettingsResponse;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.StableIncomeEntry;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.SettingsRepository;
import uz.tracker.trackerproject.repository.StableIncomeEntryRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The monthly income's history through Settings (STABLE-INCOME-HISTORY.md §1 API): PUT records an
 * amount from a month on, GET lists the history, DELETE removes an entry, the boot back-fill writes
 * the first one — and {@code Settings.monthlyStableIncome} always reads the current month's value,
 * so the deployed clients that read it keep working.
 */
class StableIncomeSettingsTest {

    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);

    private final List<StableIncomeEntry> entries = new ArrayList<>();
    private final Settings settings = new Settings();
    private TransactionRepository transactionRepository;
    private SettingsService service;

    @BeforeEach
    void setUp() {
        SettingsRepository repo = mock(SettingsRepository.class);
        settings.setId(1L);
        when(repo.findById(any())).thenReturn(Optional.of(settings));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        StableIncomeEntryRepository history = mock(StableIncomeEntryRepository.class);
        when(history.findAllByOrderByMonthAsc()).thenAnswer(i -> entries.stream()
                .sorted(Comparator.comparing(StableIncomeEntry::getMonth)).toList());
        when(history.findByMonth(any())).thenAnswer(i -> entries.stream()
                .filter(e -> e.getMonth().equals(i.getArgument(0))).findFirst());
        when(history.count()).thenAnswer(i -> (long) entries.size());
        when(history.save(any(StableIncomeEntry.class))).thenAnswer(i -> {
            StableIncomeEntry e = i.getArgument(0);
            if (!entries.contains(e)) entries.add(e);
            return e;
        });
        doAnswer(i -> entries.remove(i.<StableIncomeEntry>getArgument(0))).when(history).delete(any(StableIncomeEntry.class));
        transactionRepository = mock(TransactionRepository.class);

        service = new SettingsService(repo);
        ReflectionTestUtils.setField(service, "stableIncomeEntries", history);
        ReflectionTestUtils.setField(service, "transactionRepository", transactionRepository);
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private void entry(String month, String amount) {
        entries.add(new StableIncomeEntry(YearMonth.parse(month).atDay(1), n(amount)));
    }

    private static SettingsRequest income(String amount, String from) {
        SettingsRequest r = new SettingsRequest();
        r.setMonthlyStableIncome(n(amount));
        r.setStableIncomeFrom(from);
        return r;
    }

    private List<String> history() {
        return entries.stream().sorted(Comparator.comparing(StableIncomeEntry::getMonth))
                .map(e -> YearMonth.from(e.getMonth()) + "=" + e.getAmount().toPlainString()).toList();
    }

    // ── The boot back-fill ────────────────────────────────────────────────────

    @Test
    void theBackfillWritesOneEntryFromTheTrackingStart_andNothingOnASecondRun() {
        settings.setMonthlyStableIncome(n("7000000"));
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 9, 1));

        assertThat(service.backfillStableIncomeHistory()).isTrue();
        assertThat(history()).containsExactly("2026-09=7000000");

        settings.setMonthlyStableIncome(n("8000000"));                // whatever happens after, a rerun adds nothing
        assertThat(service.backfillStableIncomeHistory()).isFalse();
        assertThat(history()).containsExactly("2026-09=7000000");
    }

    @Test
    void theBackfillFallsBackToTheEarliestTransactionMonth_andNeedsAnIncome() {
        assertThat(service.backfillStableIncomeHistory()).isFalse();  // no income set: nothing to keep
        assertThat(entries).isEmpty();

        settings.setMonthlyStableIncome(n("7000000"));
        when(transactionRepository.findEarliestTransactionDate()).thenReturn(LocalDate.of(2026, 6, 14));
        service.backfillStableIncomeHistory();

        assertThat(history()).containsExactly("2026-06=7000000");
    }

    // ── PUT ───────────────────────────────────────────────────────────────────

    /** An old client: the amount alone applies from the current month — never back in time. */
    @Test
    void anAmountWithoutAMonthAppliesFromTheCurrentMonth() {
        settings.setMonthlyStableIncome(n("7000000"));
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 9, 1));
        entry("2026-09", "7000000");

        SettingsResponse r = service.update(income("8000000", null), OCT_1);

        assertThat(history()).containsExactly("2026-09=7000000", "2026-10=8000000");
        assertThat(r.getMonthlyStableIncome()).isEqualByComparingTo("8000000");
        assertThat(settings.getMonthlyStableIncome()).isEqualByComparingTo("8000000");
    }

    /** Saving other settings with the income as it is changes nothing. */
    @Test
    void anUnchangedAmountWithoutAMonthChangesNothing() {
        settings.setMonthlyStableIncome(n("7000000"));
        entry("2026-09", "7000000");

        service.update(income("7000000", null), OCT_1);

        assertThat(history()).containsExactly("2026-09=7000000");
    }

    /** The first amount ever applies from the first month — no question to ask. */
    @Test
    void theFirstAmountAppliesFromTheFirstMonth() {
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 9, 1));

        service.update(income("7000000", null), OCT_1);

        assertThat(history()).containsExactly("2026-09=7000000");
        assertThat(settings.getMonthlyStableIncome()).isEqualByComparingTo("7000000");
    }

    @Test
    void aMonthSentUpsertsThatMonth_keepsLaterEntries_andSettingsReadsTheCurrentMonth() {
        settings.setMonthlyStableIncome(n("7000000"));
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 8, 1));
        entry("2026-08", "7000000");
        entry("2026-11", "9000000");

        service.update(income("8000000", "2026-09"), OCT_1);
        assertThat(history()).containsExactly("2026-08=7000000", "2026-09=8000000", "2026-11=9000000");
        assertThat(settings.getMonthlyStableIncome()).isEqualByComparingTo("8000000");   // October reads September's

        service.update(income("8500000", "2026-09"), OCT_1);                             // the same month again
        assertThat(history()).containsExactly("2026-08=7000000", "2026-09=8500000", "2026-11=9000000");

        // A raise recorded ahead does not touch the current month.
        service.update(income("10000000", "2026-12"), OCT_1);
        assertThat(settings.getMonthlyStableIncome()).isEqualByComparingTo("8500000");
    }

    @Test
    void theRangeAndTheFormatAreChecked() {
        settings.setMonthlyStableIncome(n("7000000"));
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 9, 1));
        entry("2026-09", "7000000");

        assertThatThrownBy(() -> service.update(income("8000000", "2026-08"), OCT_1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2026-09 at the earliest");
        assertThatThrownBy(() -> service.update(income("8000000", "2027-11"), OCT_1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("up to 2027-10");
        assertThatThrownBy(() -> service.update(income("8000000", "October"), OCT_1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("stableIncomeFrom must be YYYY-MM, got: October");
        SettingsRequest monthOnly = new SettingsRequest();
        monthOnly.setStableIncomeFrom("2026-10");
        assertThatThrownBy(() -> service.update(monthOnly, OCT_1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs monthlyStableIncome");

        // The edges themselves are fine.
        service.update(income("8000000", "2026-09"), OCT_1);
        service.update(income("9000000", "2027-10"), OCT_1);
        assertThat(history()).containsExactly("2026-09=8000000", "2027-10=9000000");
    }

    /** Without a tracking start, the first month is the earliest transaction's. */
    @Test
    void theFirstMonthIsTheEarliestTransactionsWithoutATrackingStart() {
        settings.setMonthlyStableIncome(n("7000000"));
        entry("2026-06", "7000000");
        when(transactionRepository.findEarliestTransactionDate()).thenReturn(LocalDate.of(2026, 6, 14));

        assertThat(service.get(OCT_1).getStableIncomeFirstMonth()).isEqualTo("2026-06");
        assertThatThrownBy(() -> service.update(income("8000000", "2026-05"), OCT_1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── GET ───────────────────────────────────────────────────────────────────

    @Test
    void getListsTheHistoryOldestFirst_andTheIncomeOfTheCurrentMonth() {
        settings.setMonthlyStableIncome(n("7000000"));
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 9, 1));
        entry("2026-10", "9000000");
        entry("2026-09", "7000000");

        SettingsResponse september = service.get(LocalDate.of(2026, 9, 30));
        assertThat(september.getStableIncomeHistory()).extracting("month", "amount")
                .containsExactly(tuple("2026-09", n("7000000")), tuple("2026-10", n("9000000")));
        assertThat(september.getStableIncomeFirstMonth()).isEqualTo("2026-09");
        assertThat(september.getMonthlyStableIncome()).isEqualByComparingTo("7000000");

        // The month the raise was recorded for has come: the single value follows it.
        assertThat(service.get(OCT_1).getMonthlyStableIncome()).isEqualByComparingTo("9000000");
        assertThat(settings.getMonthlyStableIncome()).isEqualByComparingTo("9000000");
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Test
    void deletingAnEntryFallsBackToTheOneBefore_andTheLastOneCannotGo() {
        settings.setMonthlyStableIncome(n("8000000"));
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 9, 1));
        entry("2026-09", "7000000");
        entry("2026-10", "8000000");

        SettingsResponse r = service.deleteStableIncome("2026-10", OCT_1);

        assertThat(history()).containsExactly("2026-09=7000000");
        assertThat(r.getMonthlyStableIncome()).isEqualByComparingTo("7000000");      // October falls back to September's
        assertThat(service.stableIncomeFor(YearMonth.of(2026, 10))).isEqualByComparingTo("7000000");

        assertThatThrownBy(() -> service.deleteStableIncome("2026-09", OCT_1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only monthly income");
        assertThatThrownBy(() -> service.deleteStableIncome("2026-07", OCT_1))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deleteStableIncome("2026/07", OCT_1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("YYYY-MM");
        assertThat(history()).containsExactly("2026-09=7000000");
    }

    // ── stableIncomeFor ───────────────────────────────────────────────────────

    @Test
    void theValueForAMonthIsTheLatestEntryNotAfterIt_andTheFirstBeforeThem() {
        settings.setMonthlyStableIncome(n("8000000"));
        entry("2026-09", "7000000");
        entry("2026-11", "9000000");

        assertThat(service.stableIncomeFor(YearMonth.of(2026, 6))).isEqualByComparingTo("7000000");
        assertThat(service.stableIncomeFor(YearMonth.of(2026, 9))).isEqualByComparingTo("7000000");
        assertThat(service.stableIncomeFor(YearMonth.of(2026, 10))).isEqualByComparingTo("7000000");
        assertThat(service.stableIncomeFor(YearMonth.of(2026, 11))).isEqualByComparingTo("9000000");
        assertThat(service.stableIncomeFor(YearMonth.of(2027, 5))).isEqualByComparingTo("9000000");

        entries.clear();                                               // no history: Settings' single value
        assertThat(service.stableIncomeFor(YearMonth.of(2026, 6))).isEqualByComparingTo("8000000");
    }
}
