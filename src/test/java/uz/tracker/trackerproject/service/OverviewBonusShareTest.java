package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.repository.*;

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
 * Income in a bonus-flagged category adds its share to the month it arrives in: every bucket's
 * target is its percentage of (left balance + bonus). The owner asked for this back on 2026-09-17,
 * after it had been display-only since 2026-06-08.
 *
 * <p>What the bonus must NOT move is pinned just as hard: the level, the sub-level and Level 1's
 * tight/comfortable scenario still come from stable income alone, so a one-off bonus can raise a
 * month's targets without pretending the owner has moved up a tier.
 */
class OverviewBonusShareTest {

    private TransactionRepository transactionRepository;
    private BankLoanRepository bankLoanRepository;
    private OverviewService service;
    private Settings settings;

    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
        LevelAllocationRuleRepository ruleRepository = mock(LevelAllocationRuleRepository.class);
        MarkPaidRepository markPaidRepository = mock(MarkPaidRepository.class);
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        DonationRepository donationRepository = mock(DonationRepository.class);
        LevelConfigRepository levelConfigRepository = mock(LevelConfigRepository.class);
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

    /** A bonus of {@code amount} UZS received in {@code month}. */
    private void bonusIn(YearMonth month, String amount) {
        when(transactionRepository.sumBonusIncomeByCurrencyDateRange(
                Currency.UZS, month.atDay(1), month.atEndOfMonth())).thenReturn(new BigDecimal(amount));
    }

    private OverviewTierResponse tier(YearMonth month) {
        return service.getTier(month, Currency.UZS);
    }

    private static BigDecimal targetOf(OverviewTierResponse tier, String bucket) {
        return tier.getAllocation().getLines().stream()
                .filter(l -> bucket.equals(l.getBucket()))
                .map(AllocationLine::getMinAmount)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + bucket + " line"));
    }

    /** The owner's question: 10M stable income, no bills or debt (Level 1.1), and a 7M bonus. */
    @Test
    void aBonusRaisesEachTargetByThatBucketsShareOfTheBonus() {
        bonusIn(SEP, "7000000");

        OverviewTierResponse t = tier(SEP);

        assertThat(t.getBonusIncome()).isEqualByComparingTo("7000000");
        assertThat(t.getAllocationBase()).isEqualByComparingTo("17000000");   // 10M left + 7M bonus
        assertThat(targetOf(t, "DONATION")).isEqualByComparingTo("1700000");      // 10%
        assertThat(targetOf(t, "EMERGENCY")).isEqualByComparingTo("850000");      // 5%
        assertThat(targetOf(t, "INVESTMENTS")).isEqualByComparingTo("2550000");   // 15%
    }

    @Test
    void withoutABonusTheBaseIsTheLeftBalance() {
        OverviewTierResponse t = tier(SEP);

        assertThat(t.getBonusIncome()).isEqualByComparingTo("0");
        assertThat(t.getAllocationBase()).isEqualByComparingTo("10000000");
        assertThat(targetOf(t, "DONATION")).isEqualByComparingTo("1000000");
    }

    /** 14M stable income is Level 1; a 7M bonus on top must not make it Level 2. */
    @Test
    void aBonusDoesNotMoveTheLevel() {
        settings.setMonthlyStableIncome(new BigDecimal("14000000"));
        bonusIn(SEP, "7000000");

        OverviewTierResponse t = tier(SEP);

        assertThat(t.getLevel()).isEqualTo(1);
        assertThat(t.getSubLevel()).isEqualTo("1.1");
        assertThat(t.getAllocationBase()).isEqualByComparingTo("21000000");
    }

    /**
     * 6M stable income with a 2M bank installment leaves 4M — under the 5M cutoff, so Level 1.2 is
     * "tight" (5 / 2 / 8). The bonus takes the base to 11M but must not switch it to "comfortable":
     * the scenario is chosen from stable income, the bonus only scales its percentages.
     */
    @Test
    void aBonusDoesNotTurnATightMonthComfortable() {
        settings.setMonthlyStableIncome(new BigDecimal("6000000"));
        BankLoan bank = new BankLoan();
        bank.setId(1L);
        bank.setBankName("Kapitalbank");
        bank.setLoanName("Auto");
        bank.setTotalAmount(new BigDecimal("30000000"));
        bank.setCurrency(Currency.UZS);
        bank.setTakenDate(LocalDate.of(2026, 1, 10));
        bank.setMonthlyPayment(new BigDecimal("2000000"));
        when(bankLoanRepository.findAll()).thenReturn(List.of(bank));
        bonusIn(SEP, "7000000");

        OverviewTierResponse t = tier(SEP);

        assertThat(t.getAllocation().getScenarioKey()).isEqualTo("1.2.1.tight");
        assertThat(t.getAllocationBase()).isEqualByComparingTo("11000000");     // 4M left + 7M bonus
        assertThat(targetOf(t, "DONATION")).isEqualByComparingTo("550000");     // 5% of 11M
    }

    /** The bonus belongs to the month it arrived in — earlier and later months are untouched. */
    @Test
    void onlyTheMonthTheBonusArrivedInIsRaised() {
        settings.setAllocationTrackingStartMonth(AUG.atDay(1));
        bonusIn(SEP, "7000000");

        AllocationLedgerResponse ledger = service.getAllocationLedger(SEP, Currency.UZS);

        assertThat(monthRow(ledger, "2026-08").getAllocationBase()).isEqualByComparingTo("10000000");
        assertThat(monthRow(ledger, "2026-09").getAllocationBase()).isEqualByComparingTo("17000000");
        assertThat(monthRow(ledger, "2026-09").getBonus()).isEqualByComparingTo("7000000");
        assertThat(ledger.getAllocationBase()).isEqualByComparingTo("17000000");
        assertThat(ledger.getDueThisMonth()).isEqualByComparingTo("5100000");  // 30% of 17M
        assertThat(tier(OCT).getAllocationBase()).isEqualByComparingTo("10000000");
    }

    private static AllocationLedgerResponse.MonthBreakdown monthRow(AllocationLedgerResponse ledger, String month) {
        return ledger.getMonths().stream()
                .filter(m -> month.equals(m.getMonth()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ledger row for " + month));
    }
}
