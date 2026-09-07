package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation.ActionItem;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
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
 * A loan on a repayment plan must be asked for ONCE — at the amount the user committed to —
 * and must not also attract the 34%-of-remaining pay-down on top. Charging both would demand
 * roughly four times the money the user actually planned to part with, which is precisely the
 * "pay it ASAP" behaviour the plan exists to replace.
 *
 * <p>Also pins the allocation's bucket lines, which must no longer contain Stocks at all.
 */
class OverviewSetAsideActionTest {

    private LoanTakenRepository loanTakenRepository;
    private BankLoanRepository bankLoanRepository;
    private OverviewService service;
    private Settings settings;

    private static final YearMonth SEP = YearMonth.of(2026, 9);

    @BeforeEach
    void setUp() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        loanTakenRepository = mock(LoanTakenRepository.class);
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        bankLoanRepository = mock(BankLoanRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        DonationRepository donationRepository = mock(DonationRepository.class);
        InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
        LevelAllocationRuleRepository ruleRepository = mock(LevelAllocationRuleRepository.class);
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
        when(transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                any(), any(), any())).thenReturn(List.of());

        service = new OverviewService(
                transactionRepository, monthlyPaymentRepository, bankLoanRepository,
                loanTakenRepository, debtRepository, donationRepository, investmentRepository,
                ruleRepository, levelConfigRepository, markPaidRepository, settingsService);
    }

    /**
     * A plan on a loan that has not reached its payment-start month yet is excluded from every
     * figure on the page — correctly, but invisibly. The owner set 500,000/mo, saw nothing
     * anywhere, and concluded the plan had not saved. It must be named, with its start month,
     * and must NOT become a payable ask.
     */
    @Test
    void aPlanThatHasNotStartedYetIsShownAsUpcomingRatherThanVanishing() {
        notYetStartedLoan();

        List<ActionItem> actions = service.getTier(SEP, Currency.UZS).getAllocation().getActions();

        ActionItem note = actions.stream()
                .filter(a -> a.getText().contains("Ota-onam"))
                .findFirst().orElseThrow(() -> new AssertionError("upcoming plan not shown: " + texts(actions)));
        assertThat(note.getText()).contains("October 2026");
        // The amount is grouped with the formatter's own separator, so compare on digits.
        assertThat(note.getText().replaceAll("[^0-9]", "")).contains("500000");
        // Informational only: it must not ask for money, nor lock the allocation.
        assertThat(note.getAction()).isNull();
        assertThat(note.getTarget()).isNull();
        // And it is genuinely not being charged this month.
        assertThat(texts(actions)).noneMatch(txt -> txt.startsWith("Set aside"));
    }

    /**
     * The owner's real shape: a bank loan makes the scenario 1.2 rather than the debt-free 1.1,
     * so the upcoming note travels the append path instead of the early return. Both are live.
     */
    @Test
    void anUpcomingPlanIsAlsoShownAlongsideRealDebtActions() {
        BankLoan bank = new BankLoan();
        bank.setId(1L);
        bank.setBankName("Kapitalbank");
        bank.setLoanName("Auto");
        bank.setTotalAmount(new BigDecimal("20000000"));
        bank.setCurrency(Currency.UZS);
        bank.setTakenDate(LocalDate.of(2026, 1, 1));
        bank.setMonthlyPayment(new BigDecimal("1200000"));
        when(bankLoanRepository.findAll()).thenReturn(List.of(bank));
        notYetStartedLoan();

        List<ActionItem> actions = service.getTier(SEP, Currency.UZS).getAllocation().getActions();

        assertThat(texts(actions)).anyMatch(txt -> txt.startsWith("Pay bank installments"));
        assertThat(texts(actions)).anyMatch(txt -> txt.contains("Ota-onam") && txt.contains("October 2026"));
        assertThat(texts(actions)).noneMatch(txt -> txt.startsWith("Set aside"));
    }

    /** The owner's actual row: 30M from parents, 500K/mo plan, but starting next month. */
    private void notYetStartedLoan() {
        LoanTaken l = new LoanTaken();
        l.setId(1L);
        l.setLenderName("Ota-onam");
        l.setTotalAmount(new BigDecimal("30000000"));
        l.setPaidAmount(new BigDecimal("500000"));
        l.setCurrency(Currency.UZS);
        l.setBorrowedDate(LocalDate.of(2026, 6, 1));
        l.setPaymentStartDate(LocalDate.of(2026, 10, 1));   // the month AFTER the one viewed
        l.setPlannedMonthlyPayment(new BigDecimal("500000"));
        l.setStatus(RecordStatus.PARTIALLY_PAID);
        when(loanTakenRepository.findAll()).thenReturn(List.of(l));
    }

    private void loanOf(String total, String planned) {
        LoanTaken l = new LoanTaken();
        l.setId(1L);
        l.setLenderName("Bobur");
        l.setTotalAmount(new BigDecimal(total));
        l.setPaidAmount(BigDecimal.ZERO);
        l.setCurrency(Currency.UZS);
        l.setBorrowedDate(LocalDate.of(2026, 1, 5));
        l.setPaymentStartDate(LocalDate.of(2026, 1, 1));
        l.setStatus(RecordStatus.PENDING);
        if (planned != null) l.setPlannedMonthlyPayment(new BigDecimal(planned));
        when(loanTakenRepository.findAll()).thenReturn(List.of(l));
    }

    private List<ActionItem> actionsFor(String total, String planned) {
        loanOf(total, planned);
        OverviewTierResponse tier = service.getTier(SEP, Currency.UZS);
        assertThat(tier.getAllocation()).isNotNull();
        return tier.getAllocation().getActions();
    }

    @Test
    void aPlannedLoanIsAskedForAtThePlanAmount() {
        List<ActionItem> actions = actionsFor("9000000", "200000");

        ActionItem setAside = actions.stream()
                .filter(a -> a.getText().startsWith("Set aside"))
                .findFirst().orElseThrow(() -> new AssertionError("no set-aside action: " + texts(actions)));
        assertThat(setAside.getTarget()).isEqualByComparingTo("200000");
        assertThat(setAside.getAction()).isEqualTo("PAY_PERSONAL_LOAN");
    }

    @Test
    void aPlannedLoanIsNotAlsoChargedThe34PercentPayDown() {
        List<ActionItem> actions = actionsFor("9000000", "200000");

        assertThat(texts(actions)).noneMatch(txt -> txt.contains("34%"));
        // Everything asked of this loan, once: the plan and nothing more.
        BigDecimal asked = actions.stream()
                .filter(a -> "PAY_PERSONAL_LOAN".equals(a.getAction()))
                .map(ActionItem::getTarget)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(asked).isEqualByComparingTo("200000");
    }

    @Test
    void withoutAPlanTheThirtyFourPercentAskIsUnchanged() {
        List<ActionItem> actions = actionsFor("9000000", null);

        assertThat(texts(actions)).anyMatch(txt -> txt.contains("34%"));
        assertThat(texts(actions)).noneMatch(txt -> txt.startsWith("Set aside"));
        BigDecimal asked = actions.stream()
                .filter(a -> "PAY_PERSONAL_LOAN".equals(a.getAction()))
                .map(ActionItem::getTarget)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(asked).isEqualByComparingTo("3060000"); // 34% of 9M
    }

    @Test
    void stocksIsNoLongerAnAllocationBucket() {
        loanOf("9000000", null);
        OverviewTierResponse tier = service.getTier(SEP, Currency.UZS);

        assertThat(tier.getAllocation().getLines())
                .extracting(uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine::getBucket)
                .containsExactly("DONATION", "EMERGENCY", "INVESTMENTS");
    }

    /**
     * The ledger walks every month from the tracking start to the selected one, and pairs a
     * numeric accumulator against LEDGER_BUCKETS by index. Dropping Stocks shortened the name
     * array to three while the loops still counted to four, so the second loop indexed
     * LEDGER_BUCKETS[3] and the whole Overview page died with an
     * ArrayIndexOutOfBoundsException. Multi-month so the carried-balance loop runs too.
     */
    @Test
    void theLedgerRendersTheThreeLiveBucketsAndDoesNotOverrun() {
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 7, 1));
        loanOf("9000000", null);

        AllocationLedgerResponse ledger = service.getAllocationLedger(SEP, Currency.UZS);

        assertThat(ledger.getBuckets())
                .extracting(AllocationLedgerResponse.BucketLedger::getBucket)
                .containsExactly("DONATION", "EMERGENCY", "INVESTMENTS");
        assertThat(ledger.getBuckets())
                .extracting(AllocationLedgerResponse.BucketLedger::getLabel)
                .containsExactly("Donation", "Emergency", "Investments");
        assertThat(ledger.getMonths()).isNotEmpty();
        assertThat(ledger.getMonths())
                .allSatisfy(m -> assertThat(m.getLines())
                        .extracting(AllocationLedgerResponse.MonthBucketLine::getBucket)
                        .doesNotContain("STOCKS"));
    }

    private static List<String> texts(List<ActionItem> actions) {
        return actions.stream().map(ActionItem::getText).toList();
    }
}
