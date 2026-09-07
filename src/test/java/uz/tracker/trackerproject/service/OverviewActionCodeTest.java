package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.TierAllocation.ActionItem;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.LevelAllocationRule;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LevelAllocationRuleRepository;
import uz.tracker.trackerproject.repository.LevelConfigRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.MarkPaidRepository;
import uz.tracker.trackerproject.repository.MonthlyPaymentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every sentence on the Plan page's action list is composed HERE, as English prose, so the
 * language switch could never reach it: an Uzbek viewer read "Pay at least 34% of your debts /
 * borrowed money…" in English, on Plan and on Home alike. Each item therefore carries the
 * translation key for its sentence plus the values to interpolate, with the English kept as the
 * fallback for a client that has no entry for the key.
 *
 * <p>The params must be language-NEUTRAL — an amount already grouped with its unit, a month as
 * ISO "YYYY-MM" for the client to spell out, names verbatim — or the English would simply move
 * from the sentence into its holes.
 */
class OverviewActionCodeTest {

    private LoanTakenRepository loanTakenRepository;
    private BankLoanRepository bankLoanRepository;
    private MonthlyPaymentRepository monthlyPaymentRepository;
    private LevelAllocationRuleRepository ruleRepository;
    private OverviewService service;
    private Settings settings;

    private static final YearMonth SEP = YearMonth.of(2026, 9);

    @BeforeEach
    void setUp() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        loanTakenRepository = mock(LoanTakenRepository.class);
        monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        bankLoanRepository = mock(BankLoanRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        DonationRepository donationRepository = mock(DonationRepository.class);
        InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
        ruleRepository = mock(LevelAllocationRuleRepository.class);
        LevelConfigRepository levelConfigRepository = mock(LevelConfigRepository.class);
        MarkPaidRepository markPaidRepository = mock(MarkPaidRepository.class);
        SettingsService settingsService = mock(SettingsService.class);

        settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("10000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        when(settingsService.getOrCreate()).thenReturn(settings);

        when(monthlyPaymentRepository.findAll()).thenReturn(List.of());
        when(bankLoanRepository.findAll()).thenReturn(List.of());
        when(debtRepository.findAll()).thenReturn(List.of());
        when(loanTakenRepository.findAll()).thenReturn(List.of());
        when(investmentRepository.findAll()).thenReturn(List.of());
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(any(), any()))
                .thenReturn(List.of());
        when(markPaidRepository.findByKindAndRefIdAndMonth(any(), any(), any())).thenReturn(List.of());
        when(markPaidRepository.findByMonth(any())).thenReturn(List.of());
        when(levelConfigRepository.findByLevel(anyInt())).thenReturn(Optional.empty());
        when(ruleRepository.findBySubLevel(any())).thenReturn(Optional.empty());
        when(transactionRepository.sumByTypeCurrencyDateRange(any(), any(), any(), any())).thenReturn(null);
        when(transactionRepository.sumBySubTypeCurrencyDateRange(any(), any(), any(), any())).thenReturn(null);
        when(transactionRepository.sumBonusIncomeByCurrencyDateRange(any(), any(), any())).thenReturn(null);
        when(transactionRepository.sumByMonthlyPaymentIdAndDateRange(any(), any(), any())).thenReturn(null);
        when(transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                any(), any(), any())).thenReturn(List.of());

        service = new OverviewService(
                transactionRepository, monthlyPaymentRepository, bankLoanRepository,
                loanTakenRepository, debtRepository, donationRepository, investmentRepository,
                ruleRepository, levelConfigRepository, markPaidRepository, settingsService);
    }

    private List<ActionItem> actions() {
        return service.getTier(SEP, Currency.UZS).getAllocation().getActions();
    }

    private ActionItem itemWithCode(String code) {
        return actions().stream()
                .filter(a -> code.equals(a.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no item with code " + code + ", got: "
                        + actions().stream().map(ActionItem::getCode).toList()));
    }

    /** The amount param's digits — its grouping separator is the formatter's own thin space. */
    private String amountOf(ActionItem item) {
        return item.getParams().get("amount").replaceAll("[^0-9]", "");
    }

    private void bankLoan() {
        BankLoan bank = new BankLoan();
        bank.setId(1L);
        bank.setBankName("Kapitalbank");
        bank.setLoanName("Auto");
        bank.setTotalAmount(new BigDecimal("20000000"));
        bank.setCurrency(Currency.UZS);
        bank.setTakenDate(LocalDate.of(2026, 1, 1));
        bank.setMonthlyPayment(new BigDecimal("1200000"));
        when(bankLoanRepository.findAll()).thenReturn(List.of(bank));
    }

    /** The owner's real row: money from parents, on a plan that only starts next month. */
    private void loanStartingNextMonth() {
        LoanTaken l = new LoanTaken();
        l.setId(1L);
        l.setLenderName("Ota-onam");
        l.setTotalAmount(new BigDecimal("30000000"));
        l.setPaidAmount(new BigDecimal("500000"));
        l.setCurrency(Currency.UZS);
        l.setBorrowedDate(LocalDate.of(2026, 6, 1));
        l.setPaymentStartDate(LocalDate.of(2026, 10, 1));
        l.setPlannedMonthlyPayment(new BigDecimal("500000"));
        l.setStatus(RecordStatus.PARTIALLY_PAID);
        when(loanTakenRepository.findAll()).thenReturn(List.of(l));
    }

    /** A borrowed sum with no plan, so the 34% pay-down is asked for instead. */
    private void loanBeingCharged() {
        LoanTaken l = new LoanTaken();
        l.setId(2L);
        l.setLenderName("Bobur");
        l.setTotalAmount(new BigDecimal("9000000"));
        l.setPaidAmount(BigDecimal.ZERO);
        l.setCurrency(Currency.UZS);
        l.setBorrowedDate(LocalDate.of(2026, 1, 5));
        l.setPaymentStartDate(LocalDate.of(2026, 1, 1));
        l.setStatus(RecordStatus.PENDING);
        when(loanTakenRepository.findAll()).thenReturn(List.of(l));
    }

    @Test
    void everySentenceThePageComposesCarriesItsTranslationKey() {
        bankLoan();
        loanBeingCharged();

        List<ActionItem> actions = actions();

        assertThat(actions).isNotEmpty();
        assertThat(actions).allSatisfy(a -> {
            assertThat(a.getCode()).as("code for: %s", a.getText()).isNotNull();
            assertThat(a.getParams()).as("params for: %s", a.getText()).isNotNull();
            assertThat(a.getText()).isNotBlank();
        });
        // The two asks a bank loan plus borrowed money produce, each with its own sentence.
        assertThat(actions).extracting(ActionItem::getCode)
                .contains("page.plan.actionPayBank", "page.plan.action.payDebts34");
    }

    /**
     * The 34% rule and a repayment plan the user set themselves are paid through the same action,
     * so a client with only the structured fields to go on cannot tell them apart and prints one
     * sentence for both. Distinct keys are what let each row say what it actually means.
     */
    @Test
    void aPlannedSetAsideAndTheThirtyFourPercentRuleGetDifferentKeys() {
        bankLoan();
        LoanTaken planned = new LoanTaken();
        planned.setId(3L);
        planned.setLenderName("Bobur");
        planned.setTotalAmount(new BigDecimal("9000000"));
        planned.setPaidAmount(BigDecimal.ZERO);
        planned.setCurrency(Currency.UZS);
        planned.setBorrowedDate(LocalDate.of(2026, 1, 5));
        planned.setPaymentStartDate(LocalDate.of(2026, 1, 1));
        planned.setPlannedMonthlyPayment(new BigDecimal("200000"));
        planned.setStatus(RecordStatus.PENDING);
        when(loanTakenRepository.findAll()).thenReturn(List.of(planned));

        ActionItem setAside = itemWithCode("page.plan.action.setAside");

        assertThat(setAside.getAction()).isEqualTo("PAY_PERSONAL_LOAN");
        assertThat(amountOf(setAside)).isEqualTo("200000");
        assertThat(actions()).extracting(ActionItem::getCode)
                .doesNotContain("page.plan.action.payDebts34");
    }

    @Test
    void anUpcomingChargeNotePassesMachineReadableValuesNotEnglish() {
        loanStartingNextMonth();

        ActionItem note = itemWithCode("page.plan.note.loanPlanNotStarted");

        assertThat(note.getParams())
                .containsEntry("name", "Ota-onam")
                // ISO, so the client spells the month in the viewer's language.
                .containsEntry("month", "2026-10");
        assertThat(amountOf(note)).isEqualTo("500000");
        assertThat(note.getParams().get("amount")).endsWith(" UZS");
        // The English sentence stays as the fallback for a client that predates the key, and it
        // is built from the same value, so the two forms cannot quote different money.
        assertThat(note.getText()).contains(note.getParams().get("amount"));
        assertThat(note.getText()).contains("October 2026");
        assertThat(note.getAction()).isNull();
    }

    /** Home prints this one verbatim, so it was the most visible English of the lot. */
    @Test
    void theSubscriptionsPendingNoteIsTranslatable() {
        MonthlyPayment sub = new MonthlyPayment();
        sub.setId(4L);
        sub.setName("Internet");
        sub.setAmount(new BigDecimal("300000"));
        sub.setCurrency(Currency.UZS);
        sub.setDueDay(5);
        sub.setActive(true);
        when(monthlyPaymentRepository.findAll()).thenReturn(List.of(sub));

        ActionItem note = itemWithCode("page.plan.note.subscriptionsPending");

        assertThat(note.getParams()).containsExactly(java.util.Map.entry("month", "2026-09"));
        assertThat(note.getText()).contains("September 2026");
    }

    @Test
    void theMissingIncomeNoteIsTranslatable() {
        settings.setMonthlyStableIncome(null);

        ActionItem note = itemWithCode("page.plan.note.setIncome");

        assertThat(note.getParams()).isEmpty();
        assertThat(note.getText()).isEqualTo("Set monthly income to see allocation guidance.");
    }

    @Test
    void theTierScenarioNoteIsTranslatable() {
        bankLoan();

        ActionItem note = itemWithCode("page.plan.note.comfortable");

        assertThat(note.getAction()).isNull();
        assertThat(note.getParams()).isEmpty();
    }

    /**
     * The one item that must NOT be given a key: the note the user typed into the rules editor.
     * It is their sentence, in whichever language they wrote it — a key would print somebody
     * else's words in its place.
     */
    @Test
    void theUsersOwnRuleNoteIsLeftUntranslated() {
        settings.setMonthlyStableIncome(new BigDecimal("20000000")); // Level 2 — configured rules
        bankLoan();
        LevelAllocationRule rule = new LevelAllocationRule();
        rule.setLevel(2);
        rule.setSubLevel("2.2");
        rule.setDonationPercent(new BigDecimal("10"));
        rule.setEmergencyPercent(new BigDecimal("5"));
        rule.setInvestmentsPercent(new BigDecimal("15"));
        rule.setNote("Oyning oxirida qayta ko'rib chiqaman");
        when(ruleRepository.findBySubLevel(any())).thenReturn(Optional.of(rule));

        List<ActionItem> actions = actions();

        ActionItem own = actions.stream()
                .filter(a -> a.getText().equals("Oyning oxirida qayta ko'rib chiqaman"))
                .findFirst().orElseThrow(() -> new AssertionError("the user's note is missing: "
                        + actions.stream().map(ActionItem::getText).toList()));
        assertThat(own.getCode()).isNull();
        // …while everything the service worded itself in the same list still carries one.
        assertThat(actions).filteredOn(a -> a != own)
                .isNotEmpty()
                .allSatisfy(a -> assertThat(a.getCode()).isNotNull());
    }
}
