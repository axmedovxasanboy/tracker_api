package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.RepaymentType;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The owner's two kinds of borrowed money (2026-09-23): MONTHLY, repaid like a bank loan at its
 * plan; and ASAP, repaid as fast as possible — all of what was left at the month's start when that
 * is at most 70% of the stable income, else 34% of it. Every debt is ASAP.
 */
class RepaymentTypeTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final YearMonth DEC = YearMonth.of(2026, 12);

    private final List<LoanTaken> loans = new ArrayList<>();
    private final List<Debt> debts = new ArrayList<>();
    private final List<MarkPaid> marks = new ArrayList<>();
    private TransactionLedger ledger;
    private OverviewService service;

    @BeforeEach
    void setUp() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        MarkPaidRepository markPaidRepository = mock(MarkPaidRepository.class);
        SettingsService settingsService = mock(SettingsService.class);
        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("7000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(SEP.atDay(1));
        when(settingsService.getOrCreate()).thenReturn(settings);
        when(loanTakenRepository.findAll()).thenReturn(loans);
        when(debtRepository.findAll()).thenReturn(debts);
        when(markPaidRepository.findByMonth(any())).thenAnswer(inv -> marks.stream()
                .filter(m -> m.getMonth().equals(inv.getArgument(0))).toList());
        when(markPaidRepository.findByKindAndRefId(any(), any())).thenAnswer(inv -> marks.stream()
                .filter(m -> m.getKind().equals(inv.getArgument(0)) && m.getRefId().equals(inv.getArgument(1))).toList());

        ledger = new TransactionLedger(transactionRepository);
        service = new OverviewService(transactionRepository, mock(MonthlyPaymentRepository.class),
                mock(BankLoanRepository.class), loanTakenRepository, debtRepository,
                mock(DonationRepository.class), mock(InvestmentRepository.class),
                mock(LevelAllocationRuleRepository.class), mock(LevelConfigRepository.class),
                markPaidRepository, settingsService, mock(CategoryRepository.class));
    }

    private LoanTaken loan(long id, String name, String total, String paid, String plan, LocalDate borrowed) {
        LoanTaken l = new LoanTaken();
        l.setId(id);
        l.setLenderName(name);
        l.setTotalAmount(new BigDecimal(total));
        l.setPaidAmount(new BigDecimal(paid));
        if (plan != null) l.setPlannedMonthlyPayment(new BigDecimal(plan));
        l.setCurrency(Currency.UZS);
        l.setBorrowedDate(borrowed);
        l.setStatus(new BigDecimal(paid).signum() > 0 ? RecordStatus.PARTIALLY_PAID : RecordStatus.PENDING);
        loans.add(l);
        return l;
    }

    /** A repayment recorded against loan {@code id}: the transaction, and the loan's paidAmount with it. */
    private void repay(LoanTaken l, LocalDate on, String amount) {
        ledger.add(on, TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, amount).setRepaidLoanTakenId(l.getId());
        l.setPaidAmount(l.getPaidAmount().add(new BigDecimal(amount)));
    }

    private OverviewService.DebtAsk ask(YearMonth month, LocalDate asOf) {
        return service.debtAsks(month, asOf).getFirst();
    }

    // ── The rule itself ───────────────────────────────────────────────────────

    /** The owner's example: 10,000,000 on a 7,000,000 salary (70% = 4,900,000). */
    @Test
    void theAsapAskIsAllUnder70PercentOfTheIncomeElse34PercentOfWhatIsLeft() {
        BigDecimal stable = new BigDecimal("7000000");
        assertThat(OverviewService.asapAsk(new BigDecimal("10000000"), stable)).isEqualByComparingTo("3400000");
        assertThat(OverviewService.asapAsk(new BigDecimal("6600000"), stable)).isEqualByComparingTo("2244000");
        assertThat(OverviewService.asapAsk(new BigDecimal("4356000"), stable)).isEqualByComparingTo("4356000");
        assertThat(OverviewService.asapAsk(new BigDecimal("4900000"), stable)).isEqualByComparingTo("4900000");
        assertThat(OverviewService.asapAsk(new BigDecimal("4900001"), stable)).isEqualByComparingTo("1666000");
        assertThat(OverviewService.asapAsk(BigDecimal.ZERO, stable)).isEqualByComparingTo("0");
    }

    /** No type stored (a row from before it): a plan means MONTHLY, none ASAP. MONTHLY without a plan is ASAP. */
    @Test
    void theTypeIsDerivedFromThePlanWhenNoneIsStored() {
        LoanTaken parents = loan(1, "Ota-onam", "50000000", "0", "500000", null);
        LoanTaken uzum = loan(2, "Uzum Bank", "1155000", "0", null, null);
        LoanTaken zeroPlan = loan(3, "Aziz", "1000000", "0", "0", null);
        assertThat(parents.effectiveRepaymentType()).isEqualTo(RepaymentType.MONTHLY);
        assertThat(uzum.effectiveRepaymentType()).isEqualTo(RepaymentType.ASAP);
        assertThat(zeroPlan.effectiveRepaymentType()).isEqualTo(RepaymentType.ASAP);

        uzum.setRepaymentType(RepaymentType.MONTHLY);                   // no plan to pay: nothing to ask monthly
        assertThat(uzum.effectiveRepaymentType()).isEqualTo(RepaymentType.ASAP);
        parents.setRepaymentType(RepaymentType.ASAP);                   // the stored type wins
        assertThat(parents.effectiveRepaymentType()).isEqualTo(RepaymentType.ASAP);
    }

    /** The request's type, else the plan's; a MONTHLY loan needs a plan and an ASAP loan keeps none. */
    @Test
    void savingALoanKeepsItsTypeAndPlanTogether() {
        LoanTaken l = new LoanTaken();
        FinanceService.applyRepayment(l, null, new BigDecimal("500000"));
        assertThat(l.getRepaymentType()).isEqualTo(RepaymentType.MONTHLY);
        assertThat(l.getPlannedMonthlyPayment()).isEqualByComparingTo("500000");

        FinanceService.applyRepayment(l, null, null);                     // the bot's new loan
        assertThat(l.getRepaymentType()).isEqualTo(RepaymentType.ASAP);

        FinanceService.applyRepayment(l, RepaymentType.ASAP, new BigDecimal("500000"));
        assertThat(l.getRepaymentType()).isEqualTo(RepaymentType.ASAP);
        assertThat(l.getPlannedMonthlyPayment()).isNull();

        assertThatThrownBy(() -> FinanceService.applyRepayment(l, RepaymentType.MONTHLY, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── The engine's asks ─────────────────────────────────────────────────────

    /**
     * Borrowed on 2 October, 10,000,000 ASAP: nothing in September; October asks 3,400,000 of what was
     * left on the 1st; paid, November asks 34% of the 6,600,000 left; paid, December asks the last
     * 4,356,000 — under 70% of the income, so all of it.
     */
    @Test
    void anAsapLoanAsksOnWhatWasLeftAtEachMonthsStartFromTheMonthItWasBorrowed() {
        LoanTaken l = loan(7, "Uzum Bank", "10000000", "0", null, LocalDate.of(2026, 10, 2));

        assertThat(ask(SEP, SEP.atEndOfMonth()).ask()).isEqualByComparingTo("0");
        assertThat(ask(OCT, LocalDate.of(2026, 10, 5)).ask()).isEqualByComparingTo("3400000");

        repay(l, LocalDate.of(2026, 10, 20), "3400000");
        OverviewService.DebtAsk october = ask(OCT, OCT.atEndOfMonth());
        assertThat(october.ask()).isEqualByComparingTo("3400000");          // the ask stays: it is on the 1st's balance
        assertThat(october.repaid()).isEqualByComparingTo("3400000");
        assertThat(october.due()).isEqualByComparingTo("0");
        assertThat(ask(NOV, NOV.atDay(1)).ask()).isEqualByComparingTo("2244000");

        repay(l, LocalDate.of(2026, 11, 8), "2244000");
        assertThat(ask(DEC, DEC.atDay(1)).ask()).isEqualByComparingTo("4356000");
        // October, looked back on, still asked 3,400,000: its balance on the 1st is rebuilt.
        assertThat(ask(OCT, OCT.atEndOfMonth()).ask()).isEqualByComparingTo("3400000");
    }

    /** What was repaid in the month — a repayment by the day asked about, and an "already paid" mark — comes off the ask. */
    @Test
    void theMonthsRepaymentsAndMarksComeOffTheAsk() {
        LoanTaken l = loan(7, "Uzum Bank", "10000000", "0", null, LocalDate.of(2026, 10, 2));
        repay(l, LocalDate.of(2026, 10, 5), "1000000");
        repay(l, LocalDate.of(2026, 10, 25), "400000");                      // recorded for a later day
        MarkPaid mark = new MarkPaid();
        mark.setKind("PERSONAL_LOAN");
        mark.setRefId(7L);
        mark.setMonth(OCT.atDay(1));
        mark.setAmount(new BigDecimal("500000"));
        mark.setCurrency(Currency.UZS);
        marks.add(mark);
        l.setPaidAmount(l.getPaidAmount().add(new BigDecimal("500000")));

        OverviewService.DebtAsk byThe10th = ask(OCT, LocalDate.of(2026, 10, 10));
        assertThat(byThe10th.ask()).isEqualByComparingTo("3400000");
        assertThat(byThe10th.repaid()).isEqualByComparingTo("1500000");      // 1,000,000 + the mark
        assertThat(byThe10th.due()).isEqualByComparingTo("1900000");
        assertThat(ask(OCT, OCT.atEndOfMonth()).due()).isEqualByComparingTo("1500000");
    }

    /** A debt is always ASAP — under 70% of the income, all of it — whatever its payment-start month says. */
    @Test
    void aDebtIsAsapFromTheMonthItWasBorrowed() {
        Debt d = new Debt();
        d.setId(4L);
        d.setCreditorName("Shop");
        d.setTotalAmount(new BigDecimal("3000000"));
        d.setPaidAmount(BigDecimal.ZERO);
        d.setCurrency(Currency.UZS);
        d.setStatus(RecordStatus.PENDING);
        d.setBorrowedDate(LocalDate.of(2026, 9, 12));
        d.setPaymentStartDate(LocalDate.of(2026, 11, 1));
        debts.add(d);

        OverviewService.DebtAsk sep = ask(SEP, SEP.atEndOfMonth());
        assertThat(sep.kind()).isEqualTo("DEBT");
        assertThat(sep.asap()).isTrue();
        assertThat(sep.ask()).isEqualByComparingTo("3000000");
    }

    /** MONTHLY is the plan exactly as before: from its payment-start month, capped at what is left. */
    @Test
    void aMonthlyLoanAsksItsPlanFromItsStartMonth() {
        LoanTaken parents = loan(1, "Ota-onam", "50000000", "0", "500000", LocalDate.of(2026, 9, 5));
        parents.setPaymentStartDate(OCT.atDay(1));
        assertThat(ask(SEP, SEP.atEndOfMonth()).ask()).isEqualByComparingTo("0");
        assertThat(ask(OCT, OCT.atEndOfMonth()).ask()).isEqualByComparingTo("500000");

        parents.setPaidAmount(new BigDecimal("49800000"));
        assertThat(ask(NOV, NOV.atEndOfMonth()).ask()).isEqualByComparingTo("200000");
        parents.setPaidAmount(new BigDecimal("50000000"));
        assertThat(service.debtAsks(DEC, DEC.atEndOfMonth())).isEmpty();      // cleared: out of every month
    }

    /** The tier's debt charge is the sum of the month's asks, so the sub-level follows them. */
    @Test
    void theDebtChargeAndSubLevelFollowTheAsks() {
        loan(1, "Ota-onam", "50000000", "0", "500000", LocalDate.of(2026, 9, 5)).setPaymentStartDate(OCT.atDay(1));
        loan(7, "Uzum Bank", "10000000", "0", null, LocalDate.of(2026, 10, 2));

        OverviewTierResponse october = service.getTierIgnoringSubscriptions(OCT, Currency.UZS, OCT.atDay(15));
        assertThat(october.getDebtPayments()).isEqualByComparingTo("3900000");        // 500,000 + 3,400,000
        assertThat(october.getDebtBreakdown().getLoansTaken()).isEqualByComparingTo("3900000");
        assertThat(october.getSubLevel()).isEqualTo("1.2");                             // 3.9M ÷ 7M ≤ 70%
        assertThat(october.getAllocation().getActions())
                .filteredOn(a -> a.getTarget() != null)
                .extracting(a -> a.getCode(), a -> a.getTarget().stripTrailingZeros().toPlainString())
                .contains(org.assertj.core.groups.Tuple.tuple("page.plan.action.setAside", "500000"),
                        org.assertj.core.groups.Tuple.tuple("page.plan.action.payDebts34", "3400000"));

        loan(8, "Uzum Nasiya", "25000000", "0", null, LocalDate.of(2026, 10, 3));
        OverviewTierResponse heavy = service.getTierIgnoringSubscriptions(OCT, Currency.UZS, OCT.atDay(15));
        assertThat(heavy.getDebtPayments()).isEqualByComparingTo("12400000");         // + 34% of 25M
        assertThat(heavy.getSubLevel()).isEqualTo("1.3");
    }

    /** A repayment that names no loan is counted apart, for the walk and the advisor to take off the ASAP asks. */
    @Test
    void aRepaymentNamingNoLoanIsCountedApart() {
        loan(7, "Uzum Bank", "10000000", "0", null, LocalDate.of(2026, 10, 2));
        ledger.add(LocalDate.of(2026, 10, 4), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "300000");
        ledger.add(LocalDate.of(2026, 10, 20), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "200000");

        assertThat(service.unlinkedRepayments(OCT, LocalDate.of(2026, 10, 10))).isEqualByComparingTo("300000");
        assertThat(service.unlinkedRepayments(OCT, OCT.atEndOfMonth())).isEqualByComparingTo("500000");
    }
}
