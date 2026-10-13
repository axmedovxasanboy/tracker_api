package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Breakdown;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Daily;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code daily.verdict} and {@code daily.cause}: which state the day is in, and — when the pace runs
 * out — what it runs into. Both come from figures the answer already carries: the verdict from
 * {@code shortBy} / {@code runsOutOn}, the cause from the same walk run again with nothing set aside
 * at all. (The plans' monthly payments are in neither walk since 13 Oct 2026, so GOALS is gone.)
 *
 * <p>One setting throughout: 23 September, 7,000,000 a month paid on the 7th (this month's is in),
 * no debts — so October's rule sets 30% = 2,100,000 aside on 7 October, and the horizon ends on
 * 6 November, 45 days away. With 9,000,000 in the wallets:
 * <pre>
 *   the first 14 days (to 6 Oct)   net = 9,000,000 − what is reserved today
 *   day 45 (6 Nov)                 net = 9,000,000 + 7,000,000 − 2,100,000 − reservations
 *   nothing set aside              16,000,000 ÷ 45 = 355,555 → 355,000 a day
 *   the rule only                  13,900,000 ÷ 45 = 308,888 → 308,000 a day
 * </pre>
 * The pace is everyday spending since tracking began on 1 September, over 23 days.
 */
class DailyVerdictTest {

    private static final LocalDate SEP_23 = LocalDate.of(2026, 9, 23);
    private static final Category SALARY = TransactionLedger.category("Salary", false, null);

    private TransactionLedger ledger;
    private List<MonthlyPayment> bills;
    private DailyAdviceService service;

    @BeforeEach
    void setUp() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        SettingsService settingsService = mock(SettingsService.class);

        ledger = new TransactionLedger(transactionRepository);
        bills = new ArrayList<>();
        when(monthlyPaymentRepository.findAll()).thenReturn(bills);

        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("7000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 9, 1));
        when(settingsService.getOrCreate()).thenReturn(settings);

        OverviewService overview = new OverviewService(transactionRepository, monthlyPaymentRepository,
                bankLoanRepository, loanTakenRepository, debtRepository, mock(DonationRepository.class),
                mock(InvestmentRepository.class), mock(LevelAllocationRuleRepository.class),
                mock(LevelConfigRepository.class), mock(MarkPaidRepository.class), settingsService,
                mock(CategoryRepository.class));
        service = new DailyAdviceService(overview, settingsService, transactionRepository,
                monthlyPaymentRepository, bankLoanRepository, loanTakenRepository, debtRepository);

        ledger.income(LocalDate.of(2026, 9, 7), "7000000", SALARY);
    }

    /** Everyday spending so far this month: the pace is this ÷ 23. */
    private void spent(String total) {
        ledger.expense(LocalDate.of(2026, 9, 12), total);
    }

    private Daily daily(String have, String setAsideLeft) {
        return service.compute(new DailyAdviceService.Inputs(SEP_23, new BigDecimal(have), new BigDecimal("7000000"),
                BigDecimal.ZERO, new BigDecimal(setAsideLeft))).daily();
    }

    // ── verdict ───────────────────────────────────────────────────────────────

    @Test
    void ok_whenTheMoneyLastsAtTheOwnersPace() {
        spent("2300000");                                         // 100,000 a day

        Daily d = daily("9000000", "0");

        assertThat(d.getShortBy()).isNull();
        assertThat(d.getRunsOutOn()).isNull();
        assertThat(d.getVerdict()).isEqualTo("OK");
        assertThat(d.getCause()).isNull();
        assertThat(d.getSafePerDay()).isEqualByComparingTo("308000");
        assertThat(d.getSafePerDayNoSavings()).isEqualByComparingTo("355000");
    }

    @Test
    void ok_whenNothingIsSpent() {
        Daily d = daily("9000000", "0");                          // a pace of 0 never runs out

        assertThat(d.getVerdict()).isEqualTo("OK");
        assertThat(d.getCause()).isNull();
    }

    /** 8,000,000 still to set aside this month leaves 1,000,000 until payday; no goals at all. */
    @Test
    void overPace_becauseOfWhatIsSetAside() {
        spent("7360000");                                         // 320,000 a day

        Daily d = daily("9000000", "8000000");

        assertThat(d.getVerdict()).isEqualTo("OVER_PACE");
        assertThat(d.getRunsOutOn()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(d.getSafePerDay()).isEqualByComparingTo("71000");               // 1,000,000 ÷ 14
        assertThat(d.getSafePerDayNoSavings()).isEqualByComparingTo("355000");     // ≥ the pace
        assertThat(d.getCause()).isEqualTo("SAVINGS");
    }

    /** 400,000 a day is more than the money allows even with nothing set aside (355,000). */
    @Test
    void overPace_becauseThePaceItselfIsTooHigh() {
        spent("9200000");                                         // 400,000 a day

        Daily d = daily("9000000", "0");

        assertThat(d.getVerdict()).isEqualTo("OVER_PACE");
        assertThat(d.getSafePerDay()).isEqualByComparingTo("308000");
        assertThat(d.getSafePerDayNoSavings()).isEqualByComparingTo("355000");
        assertThat(d.getCause()).isEqualTo("PACE");
    }

    /** Short wins over a pace that also runs out, and has no cause. */
    @Test
    void short_whenEvenSpendingNothingLeavesAPaymentUnmet() {
        spent("2300000");
        MonthlyPayment rent = new MonthlyPayment();
        rent.setId(1L);
        rent.setName("Rent");
        rent.setAmount(new BigDecimal("4200000"));
        rent.setCurrency(Currency.UZS);
        rent.setDueDay(5);                                        // unpaid and past its day: due today
        rent.setActive(true);
        bills.add(rent);

        Daily d = daily("1000000", "0");

        assertThat(d.getShortBy()).isNotNull();
        assertThat(d.getRunsOutOn()).isNotNull();                 // the pace runs out too — it is still SHORT
        assertThat(d.getVerdict()).isEqualTo("SHORT");
        assertThat(d.getCause()).isNull();
        assertThat(d.getSafePerDay()).isEqualByComparingTo("0");
        assertThat(d.getSafePerDayNoSavings()).isEqualByComparingTo("0");          // the rent is not a saving
    }

    /** Short only because of what is set aside: the walk without savings is not short, and says how much a day. */
    @Test
    void short_becauseOfSavings_stillSaysWhatNothingSetAsideWouldLeave() {
        Daily d = daily("1000000", "3000000");

        assertThat(d.getVerdict()).isEqualTo("SHORT");
        assertThat(d.getCause()).isNull();
        assertThat(d.getSafePerDay()).isEqualByComparingTo("0");
        assertThat(d.getSafePerDayNoSavings()).isEqualByComparingTo("71000");      // 1,000,000 ÷ 14 days to payday
    }

    // ── cause: the boundaries ─────────────────────────────────────────────────

    @Test
    void theCauseIsDecidedAtOrAboveThePace() {
        BigDecimal pace = new BigDecimal("308000");
        // Exactly the pace counts as holding it.
        assertThat(DailyAdviceService.cause(new BigDecimal("308000"), pace)).isEqualTo("SAVINGS");
        assertThat(DailyAdviceService.cause(new BigDecimal("307000"), pace)).isEqualTo("PACE");
    }

    /** The same boundary through the walk: 355,000 a day is exactly what nothing set aside allows. */
    @Test
    void aPaceOfExactlyWhatNothingSetAsideAllowsIsTheSavings_oneThousandMoreIsNot() {
        spent("8165000");                                         // 355,000 a day
        Daily at = daily("9000000", "8000000");
        assertThat(at.getPaceDaily()).isEqualByComparingTo("355000");
        assertThat(at.getSafePerDayNoSavings()).isEqualByComparingTo("355000");
        assertThat(at.getVerdict()).isEqualTo("OVER_PACE");
        assertThat(at.getCause()).isEqualTo("SAVINGS");

        ledger.expense(LocalDate.of(2026, 9, 13), "23000");       // 356,000 a day
        Daily above = daily("9000000", "8000000");
        assertThat(above.getPaceDaily()).isEqualByComparingTo("356000");
        assertThat(above.getCause()).isEqualTo("PACE");
    }

    // ── breakdown: what is set aside ──────────────────────────────────────────

    @Test
    void theBreakdownsSavingsAreWhatTheRuleSetsAside() {
        Daily d = daily("9000000", "600000");

        Breakdown b = d.getBreakdown();
        assertThat(d.getTightestOn()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(b.getSavings()).isEqualByComparingTo("2700000");                // 600,000 today + October's 2,100,000
        assertThat(b.getNet()).isEqualByComparingTo("13300000");                   // 9M + 7M − 2.7M
    }

    /**
     * A saving already recorded for a later day this month is money leaving on its own day — a
     * contribution to a goal as much as a donation, though a goal's monthly payment is not counted.
     */
    @Test
    void aSavingRecordedForALaterDayCountsOnItsDay() {
        Transaction toGoal = ledger.add(LocalDate.of(2026, 9, 28), TransactionType.EXPENSE, TransactionSubType.INVESTMENT, "1000000");
        toGoal.setInvestmentId(1L);
        toGoal.setAllocationBucket(AllocationBucket.SAVINGS);
        ledger.add(LocalDate.of(2026, 9, 27), TransactionType.EXPENSE, TransactionSubType.DONATION, "300000")
                .setAllocationBucket(AllocationBucket.DONATION);

        Breakdown b = daily("9000000", "0").getBreakdown();

        assertThat(b.getSavings()).isEqualByComparingTo("3400000");                // the 28th + the 27th + October's rule
    }
}
