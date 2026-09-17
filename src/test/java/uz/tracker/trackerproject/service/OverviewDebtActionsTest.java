package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.AllocationPreviewRequest;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation.ActionItem;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LevelAllocationRule;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Plan's debt asks and the months they are charged in.
 *
 * <ul>
 *   <li>The set-aside for a loan on a repayment plan and the 34% pay-down on everything else are
 *       two asks, and each must count only the repayments made toward it. Both used to read every
 *       repayment of the month, so paying the larger ask also "met" the smaller one and unlocked the
 *       buckets while money was still owed.</li>
 *   <li>Levels 2–6 charge 34% of the ORIGINAL total like Level 1, not 34% of what remains — the
 *       sub-level and the left balance on the same page were already built from the original.</li>
 *   <li>A bank loan is charged in the months it ran, not in every month of the ledger.</li>
 *   <li>The allocation preview routes a top-up by the holding it goes into, exactly as saving does.</li>
 * </ul>
 */
class OverviewDebtActionsTest {

    private TransactionRepository transactionRepository;
    private LoanTakenRepository loanTakenRepository;
    private DebtRepository debtRepository;
    private BankLoanRepository bankLoanRepository;
    private InvestmentRepository investmentRepository;
    private LevelAllocationRuleRepository ruleRepository;
    private MarkPaidRepository markPaidRepository;
    private OverviewService service;
    private Settings settings;

    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        loanTakenRepository = mock(LoanTakenRepository.class);
        debtRepository = mock(DebtRepository.class);
        bankLoanRepository = mock(BankLoanRepository.class);
        investmentRepository = mock(InvestmentRepository.class);
        ruleRepository = mock(LevelAllocationRuleRepository.class);
        markPaidRepository = mock(MarkPaidRepository.class);
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

    // ── fixtures ────────────────────────────────────────────────────────────────

    /** 9M from parents on a 500 000/mo plan (loan 1), plus a 6M debt with no plan (debt 2). */
    private void plannedLoanAndPlainDebt() {
        LoanTaken planned = new LoanTaken();
        planned.setId(1L);
        planned.setLenderName("Ota-onam");
        planned.setTotalAmount(new BigDecimal("9000000"));
        planned.setPaidAmount(BigDecimal.ZERO);
        planned.setCurrency(Currency.UZS);
        planned.setBorrowedDate(LocalDate.of(2026, 1, 5));
        planned.setPaymentStartDate(LocalDate.of(2026, 1, 1));
        planned.setPlannedMonthlyPayment(new BigDecimal("500000"));
        planned.setStatus(RecordStatus.PENDING);
        when(loanTakenRepository.findAll()).thenReturn(List.of(planned));

        Debt debt = new Debt();
        debt.setId(2L);
        debt.setCreditorName("Bobur");
        debt.setTotalAmount(new BigDecimal("6000000"));
        debt.setPaidAmount(BigDecimal.ZERO);
        debt.setCurrency(Currency.UZS);
        debt.setBorrowedDate(LocalDate.of(2026, 1, 5));
        debt.setPaymentStartDate(LocalDate.of(2026, 1, 1));
        debt.setStatus(RecordStatus.PENDING);
        when(debtRepository.findAll()).thenReturn(List.of(debt));
    }

    /** This month's repayment transactions: the whole total, and the part paid toward planned loans. */
    private void repaidThisMonth(String total, String toPlannedLoans) {
        when(transactionRepository.sumBySubTypeCurrencyDateRange(
                eq(TransactionSubType.LOAN_REPAYMENT), any(), any(), any())).thenReturn(new BigDecimal(total));
        when(transactionRepository.sumRepaymentsToLoansTaken(any(), any(), any(), any()))
                .thenReturn(new BigDecimal(toPlannedLoans));
    }

    private BankLoan bankLoan(LocalDate taken, LocalDate end) {
        BankLoan bank = new BankLoan();
        bank.setId(9L);
        bank.setBankName("Kapitalbank");
        bank.setLoanName("Auto");
        bank.setTotalAmount(new BigDecimal("20000000"));
        bank.setCurrency(Currency.UZS);
        bank.setTakenDate(taken);
        bank.setEndDate(end);
        bank.setMonthlyPayment(new BigDecimal("1200000"));
        when(bankLoanRepository.findAll()).thenReturn(List.of(bank));
        return bank;
    }

    private OverviewTierResponse tier(YearMonth month) {
        return service.getTier(month, Currency.UZS);
    }

    private static ActionItem withCode(OverviewTierResponse tier, String code) {
        return tier.getAllocation().getActions().stream()
                .filter(a -> code.equals(a.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " action in: "
                        + tier.getAllocation().getActions().stream().map(ActionItem::getText).toList()));
    }

    private static boolean hasPayBank(OverviewTierResponse tier) {
        return tier.getAllocation().getActions().stream().anyMatch(a -> "PAY_BANK".equals(a.getAction()));
    }

    // ── each repayment counts toward the ask it pays ────────────────────────────

    @Test
    void payingTheDebtDoesNotAlsoMeetThePlannedSetAside() {
        plannedLoanAndPlainDebt();
        repaidThisMonth("2040000", "0");        // 34% of the 6M debt, nothing toward the plan

        OverviewTierResponse t = tier(SEP);

        assertThat(withCode(t, "page.plan.action.payDebts34").getPaid()).isEqualByComparingTo("2040000");
        assertThat(withCode(t, "page.plan.action.setAside").getPaid()).isEqualByComparingTo("0");
        assertThat(t.getAllocation().isAllocationLocked()).isTrue();
    }

    @Test
    void payingThePlanDoesNotAlsoCountTowardTheThirtyFourPercentAsk() {
        plannedLoanAndPlainDebt();
        repaidThisMonth("500000", "500000");    // exactly the plan, paid to the planned loan

        OverviewTierResponse t = tier(SEP);

        assertThat(withCode(t, "page.plan.action.setAside").getPaid()).isEqualByComparingTo("500000");
        assertThat(withCode(t, "page.plan.action.payDebts34").getPaid()).isEqualByComparingTo("0");
        assertThat(t.getAllocation().isAllocationLocked()).isTrue();
    }

    @Test
    void payingBothAsksInFullUnlocksTheBuckets() {
        plannedLoanAndPlainDebt();
        repaidThisMonth("2540000", "500000");

        assertThat(tier(SEP).getAllocation().isAllocationLocked()).isFalse();
    }

    /** An "already paid" mark names its loan or debt, so it counts toward that one's ask too. */
    @Test
    void anAlreadyPaidMarkCountsTowardTheAskOfTheLoanItNames() {
        plannedLoanAndPlainDebt();
        when(markPaidRepository.findByMonth(SEP.atDay(1))).thenReturn(List.of(
                mark("PERSONAL_LOAN", 1L, "500000"),
                mark("DEBT", 2L, "2040000")));

        OverviewTierResponse t = tier(SEP);

        assertThat(withCode(t, "page.plan.action.setAside").getPaid()).isEqualByComparingTo("500000");
        assertThat(withCode(t, "page.plan.action.payDebts34").getPaid()).isEqualByComparingTo("2040000");
        assertThat(t.getAllocation().isAllocationLocked()).isFalse();
    }

    private static MarkPaid mark(String kind, Long refId, String amount) {
        MarkPaid m = new MarkPaid();
        m.setKind(kind);
        m.setRefId(refId);
        m.setMonth(SEP.atDay(1));
        m.setAmount(new BigDecimal(amount));
        m.setCurrency(Currency.UZS);
        return m;
    }

    // ── Levels 2–6 charge the same 34% as Level 1 ───────────────────────────────

    @Test
    void atLevelTwoTheDebtAskIsThirtyFourPercentOfTheOriginalTotal() {
        settings.setMonthlyStableIncome(new BigDecimal("20000000"));   // Level 2
        LoanTaken loan = new LoanTaken();
        loan.setId(3L);
        loan.setLenderName("Aziz");
        loan.setTotalAmount(new BigDecimal("9000000"));
        loan.setPaidAmount(new BigDecimal("3000000"));
        loan.setCurrency(Currency.UZS);
        loan.setBorrowedDate(LocalDate.of(2026, 1, 5));
        loan.setPaymentStartDate(LocalDate.of(2026, 1, 1));
        loan.setStatus(RecordStatus.PARTIALLY_PAID);
        when(loanTakenRepository.findAll()).thenReturn(List.of(loan));
        LevelAllocationRule rule = new LevelAllocationRule();
        rule.setLevel(2);
        rule.setSubLevel("2.2");
        rule.setDonationPercent(new BigDecimal("10"));
        rule.setEmergencyPercent(new BigDecimal("5"));
        rule.setInvestmentsPercent(new BigDecimal("15"));
        when(ruleRepository.findBySubLevel("2.2")).thenReturn(Optional.of(rule));

        OverviewTierResponse t = tier(SEP);

        assertThat(t.getSubLevel()).isEqualTo("2.2");
        // 34% of the original 9M, which is also what the debt charge and the left balance use —
        // not 34% of the 6M still owed (2 040 000).
        assertThat(t.getDebtPayments()).isEqualByComparingTo("3060000");
        assertThat(withCode(t, "page.plan.action.payDebts34").getTarget()).isEqualByComparingTo("3060000");
    }

    // ── a bank loan is charged in the months it ran ─────────────────────────────

    @Test
    void aBankLoanIsNotChargedBeforeTheMonthItWasTaken() {
        bankLoan(LocalDate.of(2026, 10, 5), null);

        assertThat(tier(SEP).getDebtPayments()).isEqualByComparingTo("0");
        assertThat(hasPayBank(tier(SEP))).isFalse();
        assertThat(tier(OCT).getDebtPayments()).isEqualByComparingTo("1200000");
        assertThat(hasPayBank(tier(OCT))).isTrue();
    }

    @Test
    void aBankLoanIsStillChargedInTheMonthItEndsAndNotAfter() {
        bankLoan(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 8, 20));

        assertThat(tier(AUG).getDebtPayments()).isEqualByComparingTo("1200000");
        assertThat(tier(SEP).getDebtPayments()).isEqualByComparingTo("0");
    }

    @Test
    void theLedgerChargesEachMonthOnlyTheBankLoansThatRanInIt() {
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 7, 1));
        bankLoan(LocalDate.of(2026, 9, 1), null);

        AllocationLedgerResponse ledger = service.getAllocationLedger(SEP, Currency.UZS);

        assertThat(baseOf(ledger, "2026-07")).isEqualByComparingTo("10000000");
        assertThat(baseOf(ledger, "2026-08")).isEqualByComparingTo("10000000");
        assertThat(baseOf(ledger, "2026-09")).isEqualByComparingTo("8800000");
    }

    private static BigDecimal baseOf(AllocationLedgerResponse ledger, String month) {
        return ledger.getMonths().stream()
                .filter(m -> month.equals(m.getMonth()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ledger row for " + month))
                .getAllocationBase();
    }

    // ── the preview routes a top-up by its holding, like the save does ──────────

    @Test
    void anInvestmentIntoTheEmergencyFundIsPreviewedAsEmergency() {
        when(investmentRepository.findById(6L)).thenReturn(Optional.of(holding(6L, true)));

        assertThat(preview(TransactionSubType.INVESTMENT, 6L).getBucket()).isEqualTo("EMERGENCY");
    }

    @Test
    void anEmergencyContributionIntoAPlainHoldingIsPreviewedWhereItWillBeBooked() {
        when(investmentRepository.findById(5L)).thenReturn(Optional.of(holding(5L, false)));

        assertThat(preview(TransactionSubType.EMERGENCY_CONTRIBUTION, 5L).getBucket()).isEqualTo("INVESTMENTS");
    }

    private uz.tracker.trackerproject.dto.response.AllocationPreviewResponse preview(
            TransactionSubType subType, Long investmentId) {
        AllocationPreviewRequest req = new AllocationPreviewRequest();
        req.setSubType(subType);
        req.setInvestmentId(investmentId);
        req.setAmount(new BigDecimal("100000"));
        req.setTransactionDate(SEP.atDay(10));
        return service.previewAllocation(req, Currency.UZS);
    }

    private static Investment holding(long id, boolean emergencyFund) {
        Investment i = new Investment();
        i.setId(id);
        i.setName(emergencyFund ? "Emergency fund" : "Bonds");
        i.setType(InvestmentType.OTHER);
        i.setInvestedAmount(new BigDecimal("1000000"));
        i.setCurrency(Currency.UZS);
        i.setPurchaseDate(LocalDate.of(2026, 2, 1));
        i.setEmergencyFund(emergencyFund);
        return i;
    }
}
