package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.TransactionRequest;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Salary for which month": the owner's September salary is sometimes paid on 3 October. Marked as
 * September's, it is September's salary received — and October's is not doubled. (Since 2026-09-30
 * the salary is not in the savings base at all; a bonus marked the same way still is.)
 */
class SalaryMonthTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);

    private final List<Category> categories = new ArrayList<>();
    private TransactionLedger ledger;
    private OverviewService service;
    private Category salary;
    private Category bonus;

    @BeforeEach
    void setUp() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        SettingsService settingsService = mock(SettingsService.class);
        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("7000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(SEP.atDay(1));
        when(settingsService.getOrCreate()).thenReturn(settings);
        when(categoryRepository.findAll()).thenReturn(categories);
        ledger = new TransactionLedger(transactionRepository);
        service = new OverviewService(transactionRepository, mock(MonthlyPaymentRepository.class),
                mock(BankLoanRepository.class), mock(LoanTakenRepository.class), mock(DebtRepository.class),
                mock(DonationRepository.class), mock(InvestmentRepository.class),
                mock(LevelAllocationRuleRepository.class), mock(LevelConfigRepository.class),
                mock(MarkPaidRepository.class), settingsService, categoryRepository);

        salary = TransactionLedger.category("Salary", false, null);
        salary.setId(1L);
        salary.setType(CategoryType.INCOME);
        bonus = TransactionLedger.category("Bonus", true, salary);
        bonus.setId(2L);
        bonus.setType(CategoryType.INCOME);
        categories.add(salary);
        categories.add(bonus);
        // September: the advance on the 15th, the salary itself on 3 October, marked as September's.
        ledger.income(LocalDate.of(2026, 9, 15), "2500000", salary);
        ledger.income(LocalDate.of(2026, 10, 3), "5000000", salary).setSalaryMonth(SEP.atDay(1));
    }

    private OverviewTierResponse tier(YearMonth month, LocalDate asOf) {
        return service.getTierIgnoringSubscriptions(month, Currency.UZS, asOf);
    }

    @Test
    void aSalaryPaidOnTheThirdOfOctoberCountsInSeptember() {
        LocalDate oct5 = LocalDate.of(2026, 10, 5);
        assertThat(tier(SEP, oct5).getSalaryReceived()).isEqualByComparingTo("7500000");
        // Before it came, September had the advance alone.
        assertThat(tier(SEP, LocalDate.of(2026, 10, 1)).getSalaryReceived()).isEqualByComparingTo("2500000");
        // The base is the stable income either way: a salary never moves it.
        assertThat(tier(SEP, oct5).getAllocationBase()).isEqualByComparingTo("7000000");
    }

    /** October's own salary is its advance alone — not the 5M that came on its 3rd for September. */
    @Test
    void octobersSalaryIsNotDoubled() {
        ledger.income(LocalDate.of(2026, 10, 15), "2500000", salary);                // October's advance
        OverviewTierResponse oct = tier(OCT, LocalDate.of(2026, 10, 20));
        assertThat(oct.getSalaryReceived()).isEqualByComparingTo("2500000");
        assertThat(oct.getAllocationBase()).isEqualByComparingTo("7000000");
    }

    /** A bonus marked as September's is September's bonus too. */
    @Test
    void aBonusCountsInItsSalaryMonth() {
        ledger.income(LocalDate.of(2026, 10, 3), "1000000", bonus).setSalaryMonth(SEP.atDay(1));
        assertThat(tier(SEP, LocalDate.of(2026, 10, 5)).getBonusIncome()).isEqualByComparingTo("1000000");
        assertThat(tier(SEP, LocalDate.of(2026, 10, 5)).getAllocationBase()).isEqualByComparingTo("8000000");
        assertThat(tier(OCT, LocalDate.of(2026, 10, 5)).getBonusIncome()).isEqualByComparingTo("0");
        assertThat(tier(OCT, LocalDate.of(2026, 10, 5)).getAllocationBase()).isEqualByComparingTo("7000000");
    }

    // ── Writing it ────────────────────────────────────────────────────────────

    private static TransactionRequest income(LocalDate date) {
        TransactionRequest r = new TransactionRequest();
        r.setType(TransactionType.INCOME);
        r.setSubType(TransactionSubType.REGULAR_INCOME);
        r.setAmount(new BigDecimal("5000000"));
        r.setCurrency(Currency.UZS);
        r.setTransactionDate(date);
        return r;
    }

    /**
     * Stored as the month's 1st; an edit without the key (the bot) keeps it, an explicit null clears
     * it; more than a month from the date is refused; on anything but regular income it is dropped.
     */
    @Test
    void theSalaryMonthIsKeptClearedOrRefusedOnWrite() {
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(80L);
            return t;
        });
        TransactionService tx = new TransactionService(repo, mock(CategoryRepository.class), mock(CardRepository.class),
                mock(CashBalanceRepository.class), mock(FinanceService.class), mock(MonthCloseService.class),
                mock(SettingsService.class), mock(LoanGivenRepository.class), mock(LoanTakenRepository.class),
                mock(DonationRepository.class), mock(InvestmentRepository.class));

        TransactionRequest create = income(LocalDate.of(2026, 10, 3));
        create.setSalaryMonth(SEP);
        assertThat(tx.create(create).getSalaryMonth()).isEqualTo(SEP);
        Transaction stored = new Transaction();
        stored.setId(80L);
        stored.setType(TransactionType.INCOME);
        stored.setSubType(TransactionSubType.REGULAR_INCOME);
        stored.setAmount(new BigDecimal("5000000"));
        stored.setCurrency(Currency.UZS);
        stored.setTransactionDate(LocalDate.of(2026, 10, 3));
        stored.setSalaryMonth(LocalDate.of(2026, 9, 17));
        assertThat(stored.getSalaryMonth()).isEqualTo(SEP.atDay(1));
        when(repo.findById(80L)).thenReturn(Optional.of(stored));

        tx.update(80L, income(LocalDate.of(2026, 10, 3)));                              // no key: kept
        assertThat(stored.getSalaryMonth()).isEqualTo(SEP.atDay(1));

        TransactionRequest early = income(LocalDate.of(2026, 9, 30));                   // October's, paid early
        early.setSalaryMonth(OCT);
        tx.update(80L, early);
        assertThat(stored.getSalaryMonth()).isEqualTo(OCT.atDay(1));

        TransactionRequest cleared = income(LocalDate.of(2026, 9, 30));
        cleared.setSalaryMonth(null);
        tx.update(80L, cleared);
        assertThat(stored.getSalaryMonth()).isNull();

        TransactionRequest far = income(LocalDate.of(2026, 10, 3));
        far.setSalaryMonth(YearMonth.of(2026, 8));
        assertThatThrownBy(() -> tx.create(far)).isInstanceOf(IllegalArgumentException.class);

        TransactionRequest spend = income(LocalDate.of(2026, 10, 3));
        spend.setType(TransactionType.EXPENSE);
        spend.setSubType(TransactionSubType.REGULAR_EXPENSE);
        spend.setSalaryMonth(SEP);
        assertThat(tx.create(spend).getSalaryMonth()).isNull();
    }

    /** On the wire it is 'YYYY-MM' both ways; a request without the key says so. */
    @Test
    void theSalaryMonthTravelsAsYearMonth() throws Exception {
        tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder()
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        TransactionRequest sent = json.readValue("{\"salaryMonth\":\"2026-09\"}", TransactionRequest.class);
        assertThat(sent.getSalaryMonth()).isEqualTo(SEP);
        assertThat(sent.salaryMonthGiven()).isTrue();
        assertThat(json.readValue("{\"amount\":1}", TransactionRequest.class).salaryMonthGiven()).isFalse();

        Transaction t = new Transaction();
        t.setType(TransactionType.INCOME);
        t.setAmount(new BigDecimal("5000000"));
        t.setTransactionDate(LocalDate.of(2026, 10, 3));
        t.setSalaryMonth(SEP.atDay(1));
        assertThat(json.writeValueAsString(uz.tracker.trackerproject.dto.response.TransactionResponse.from(t)))
                .contains("\"salaryMonth\":\"2026-09\"");
    }

    /** A part's slot: its day, and whether it comes a month early, in its own month, or a month late. */
    @Test
    void aPartsSlotRoundTrips() {
        LocalDate oct3 = LocalDate.of(2026, 10, 3);
        assertThat(DailyAdviceService.slot(SEP, oct3)).isEqualTo(203);
        assertThat(DailyAdviceService.arrival(OCT, 203)).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(DailyAdviceService.slot(OCT, LocalDate.of(2026, 9, 30))).isEqualTo(30);
        assertThat(DailyAdviceService.arrival(NOV, 30)).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(DailyAdviceService.slot(SEP, LocalDate.of(2026, 9, 15))).isEqualTo(115);
    }

    private static final YearMonth NOV = YearMonth.of(2026, 11);
}
