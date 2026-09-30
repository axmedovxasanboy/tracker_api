package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.ProfileResponse;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which borrowed money makes the savings rule lighter (the owner's rule, 2026-09-30): money to pay
 * back ASAP always does; money paid monthly only when the monthly payments are MORE than 10% of the
 * stable income. Here: 8M income (10% = 800,000), 5.3M of bills, a 400,000 bank installment.
 */
class RuleDebtTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);

    private final List<BankLoan> banks = new ArrayList<>();
    private final List<LoanTaken> loans = new ArrayList<>();
    private final List<Debt> debts = new ArrayList<>();
    private TransactionRepository transactionRepository;
    private OverviewService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        MonthlyPaymentRepository bills = mock(MonthlyPaymentRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        SettingsService settingsService = mock(SettingsService.class);
        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("8000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(OCT.atDay(1));
        when(settingsService.getOrCreate()).thenReturn(settings);
        MonthlyPayment rent = new MonthlyPayment();
        rent.setId(1L);
        rent.setName("Rent and school");
        rent.setAmount(new BigDecimal("5300000"));
        rent.setCurrency(Currency.UZS);
        rent.setDueDay(10);
        rent.setActive(true);
        when(bills.findAll()).thenReturn(List.of(rent));
        when(bankLoanRepository.findAll()).thenReturn(banks);
        when(loanTakenRepository.findAll()).thenReturn(loans);
        when(debtRepository.findAll()).thenReturn(debts);
        new TransactionLedger(transactionRepository);
        service = new OverviewService(transactionRepository, bills, bankLoanRepository, loanTakenRepository,
                debtRepository, mock(DonationRepository.class), mock(InvestmentRepository.class),
                mock(LevelAllocationRuleRepository.class), mock(LevelConfigRepository.class),
                mock(MarkPaidRepository.class), settingsService, mock(CategoryRepository.class));
    }

    private void bank(String monthly) {
        BankLoan b = new BankLoan();
        b.setId(5L);
        b.setMonthlyPayment(new BigDecimal(monthly));
        b.setTotalAmount(new BigDecimal("48000000"));
        b.setCurrency(Currency.UZS);
        b.setTakenDate(LocalDate.of(2026, 1, 1));
        banks.add(b);
    }

    private void monthlyLoan(String plan) {
        LoanTaken l = new LoanTaken();
        l.setId(7L);
        l.setLenderName("Ota-onam");
        l.setTotalAmount(new BigDecimal("50000000"));
        l.setPaidAmount(BigDecimal.ZERO);
        l.setPlannedMonthlyPayment(new BigDecimal(plan));
        l.setPaymentStartDate(OCT.atDay(1));
        l.setBorrowedDate(LocalDate.of(2026, 9, 1));
        l.setCurrency(Currency.UZS);
        l.setStatus(RecordStatus.PENDING);
        loans.add(l);
    }

    private void asapDebt(String amount) {
        Debt d = new Debt();
        d.setId(9L);
        d.setCreditorName("Shop");
        d.setTotalAmount(new BigDecimal(amount));
        d.setPaidAmount(BigDecimal.ZERO);
        d.setBorrowedDate(OCT.atDay(2));
        d.setCurrency(Currency.UZS);
        d.setStatus(RecordStatus.PENDING);
        debts.add(d);
    }

    private OverviewTierResponse tier() {
        return service.getTierIgnoringSubscriptions(OCT, Currency.UZS, OCT_5);
    }

    private static List<String> percents(OverviewTierResponse t) {
        return t.getAllocation().getLines().stream()
                .map(l -> l.isRecommended() ? l.getMinPercent().toPlainString() : "0").toList();
    }

    private ProfileResponse.Rule profileRule() {
        return new ProfileService(service, transactionRepository).profile(OCT_5, "owner").getRule();
    }

    /** The owner's case: a bank loan and 500,000 a month to the parents — the rule stays bank-loan-only, 5 / 2 / 8. */
    @Test
    void aSmallMonthlyLoanLeavesTheBankLoanRuleAsItIs() {
        bank("400000");
        monthlyLoan("500000");

        OverviewTierResponse t = tier();
        assertThat(t.getAllocation().getScenarioKey()).isEqualTo("1.2.1.tight");
        assertThat(percents(t)).containsExactly("5", "2", "8");
        // The payments are what they were: 900,000 of debt payments, and the plan is still asked.
        assertThat(t.getDebtPayments()).isEqualByComparingTo("900000");
        assertThat(t.getAllocation().getActions()).anyMatch(a -> "page.plan.action.setAside".equals(a.getCode()));
        ProfileResponse.Rule rule = profileRule();
        assertThat(rule.getReason()).isEqualTo("BANK_LOAN_TIGHT");
        assertThat(rule.isSmallMonthlyLoans()).isTrue();
        assertThat(rule.getMonthlyLoanLimit()).isEqualByComparingTo("800000");
    }

    /** More than 10% of the income a month: the rule is lighter — bank AND debts, 5 / 0 / 5. */
    @Test
    void aMonthlyLoanAboveTenPercentLightensTheRule() {
        bank("400000");
        monthlyLoan("900000");

        assertThat(tier().getAllocation().getScenarioKey()).isEqualTo("1.2.3");
        assertThat(percents(tier())).containsExactly("5", "0", "5");
        assertThat(profileRule().isSmallMonthlyLoans()).isFalse();
    }

    /** Exactly 10% is not more than 10%: the rule is not lighter. */
    @Test
    void exactlyTenPercentDoesNotLightenIt() {
        bank("400000");
        monthlyLoan("800000");

        assertThat(tier().getAllocation().getScenarioKey()).isEqualTo("1.2.1.tight");
    }

    /** Money to pay back ASAP always counts, whatever its size — and beside a small monthly loan too. */
    @Test
    void anAsapLoanOfAnySizeLightensIt() {
        bank("400000");
        asapDebt("100000");
        assertThat(tier().getAllocation().getScenarioKey()).isEqualTo("1.2.3");

        monthlyLoan("500000");
        assertThat(tier().getAllocation().getScenarioKey()).isEqualTo("1.2.3");
        assertThat(tier().getDebtBreakdown().getCountedForRule()).isEqualByComparingTo("100000");
    }

    /** No bank loan and only a small monthly loan: the rule of someone without debt, 10 / 5 / 15 — the plan still to pay. */
    @Test
    void withoutABankLoanASmallMonthlyLoanIsTheNoDebtRule() {
        monthlyLoan("500000");

        OverviewTierResponse t = tier();
        assertThat(t.getAllocation().getScenarioKey()).isEqualTo("1.1");
        assertThat(t.getSubLevel()).isEqualTo("1.1");
        assertThat(percents(t)).containsExactly("10", "5", "15");
        assertThat(t.getAllocation().getActions()).anyMatch(a -> "page.plan.action.setAside".equals(a.getCode()));
        assertThat(t.getAllocation().isAllocationLocked()).isTrue();
        assertThat(profileRule().getReason()).isEqualTo("NO_DEBT");
    }

    /** Heavy debt is still every payment against the income: 5.5M + 500,000 of 8M is over 70%. */
    @Test
    void heavyDebtStillCountsEveryPayment() {
        bank("5500000");
        monthlyLoan("500000");

        assertThat(tier().getAllocation().getScenarioKey()).isEqualTo("1.3");
        assertThat(tier().getSubLevel()).isEqualTo("1.3");
    }

    /** Tight or comfortable still takes every payment off: 8M − 0.4M − 0.5M with no bills is comfortable; with 2.5M of them, tight. */
    @Test
    void theCutoffStillSubtractsEveryPayment() {
        assertThat(OverviewService.computeLevel1Plan(new BigDecimal("8000000"), BigDecimal.ZERO,
                new BigDecimal("400000"), BigDecimal.ZERO, new BigDecimal("500000"), BigDecimal.ZERO,
                null, new BigDecimal("5000000")).calcBaseUzs()).isEqualByComparingTo("7100000");
        assertThat(OverviewService.computeLevel1Plan(new BigDecimal("8000000"), new BigDecimal("2500000"),
                new BigDecimal("400000"), BigDecimal.ZERO, new BigDecimal("500000"), BigDecimal.ZERO,
                null, new BigDecimal("5000000")).scenarioKey()).isEqualTo("1.2.1.tight");     // 4.6M left
    }
}
