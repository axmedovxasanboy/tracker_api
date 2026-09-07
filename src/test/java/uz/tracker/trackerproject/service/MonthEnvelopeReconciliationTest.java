package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.tracker.trackerproject.dto.request.MonthCloseRequest;
import uz.tracker.trackerproject.dto.response.MonthClosePreviewResponse;
import uz.tracker.trackerproject.dto.response.MonthSummaryResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.entity.Donation;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.entity.MonthClose;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.Transaction;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One bucket, one month, one "paid" figure — whichever screen asks for it, and whether the month
 * is open or closed. The Plan (tier), the Months page and the close preview all quote recorded
 * payments PLUS "already paid" marks. The irreversible snapshot still stores the recorded half
 * alone — {@code everydaySpend = totalSpent − taggedRecorded} balances against real wallet
 * movement, and a closed month can never be reopened — but reading a closed month adds the marks
 * back on top of those frozen columns, so closing changes no figure the user is looking at.
 *
 * <p>That addition is legitimate because a closed month's marks are as immutable as its snapshot:
 * {@code FinanceService.markPaid} and {@code deleteMark} both go through
 * {@link MonthCloseService#assertMonthOpen}, so they can be neither created nor removed afterwards.
 *
 * <p>This wires the REAL {@link OverviewService} into the REAL {@link MonthCloseService} — mocking
 * only the repositories — because the regression it guards was precisely that the two services
 * called the same helper with different arguments. A test that mocked OverviewService away would
 * have agreed with itself while the app disagreed with itself.
 */
class MonthEnvelopeReconciliationTest {

    private TransactionRepository transactionRepository;
    private DonationRepository donationRepository;
    private InvestmentRepository investmentRepository;
    private MarkPaidRepository markPaidRepository;
    private MonthlyPaymentRepository monthlyPaymentRepository;
    private CardRepository cardRepository;
    private CashBalanceRepository cashBalanceRepository;
    private MonthCloseRepository monthCloseRepository;
    private OverviewService overviewService;
    private MonthCloseService monthCloseService;

    /** The month just gone: always in the past, so {@link MonthCloseService#close} accepts it. */
    private static final YearMonth MONTH = YearMonth.now().minusMonths(1);
    private static final LocalDate MONTH_START = MONTH.atDay(1);
    private static final LocalDate MONTH_END = MONTH.atEndOfMonth();
    private static final LocalDate PRIOR_END = MONTH_START.minusDays(1);

    // Recorded — money that actually left a wallet this month.
    private static final BigDecimal DONATED = new BigDecimal("50000");
    private static final BigDecimal EMERGENCY = new BigDecimal("120000");
    private static final BigDecimal INVESTED = new BigDecimal("200000");
    private static final BigDecimal SAVED = new BigDecimal("300000");
    private static final BigDecimal STOCKS = new BigDecimal("400000");
    private static final BigDecimal RECORDED_TOTAL = new BigDecimal("1070000");

    // Marked — declared settled, no transaction, no wallet touched. The audit's own 234.200
    // for Donation, plus a Stocks mark that no marked* field of its own reports.
    private static final BigDecimal DONATION_MARK = new BigDecimal("234200");
    private static final BigDecimal STOCKS_MARK = new BigDecimal("15000");
    private static final BigDecimal MARKED_TOTAL = new BigDecimal("249200");

    // Wallet movement, used only by the close.
    private static final BigDecimal START_BALANCE = new BigDecimal("1000000");
    private static final BigDecimal INCOME = new BigDecimal("5000000");
    private static final BigDecimal COMPUTED_AT_MONTH_END = new BigDecimal("3000000");
    private static final BigDecimal ENTERED_AT_CLOSE = new BigDecimal("2500000");

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        donationRepository = mock(DonationRepository.class);
        investmentRepository = mock(InvestmentRepository.class);
        markPaidRepository = mock(MarkPaidRepository.class);
        monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        cardRepository = mock(CardRepository.class);
        cashBalanceRepository = mock(CashBalanceRepository.class);
        monthCloseRepository = mock(MonthCloseRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        SettingsService settingsService = mock(SettingsService.class);

        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("10000000")); // Level 1, no debt
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(MONTH_START);
        when(settingsService.getOrCreate()).thenReturn(settings);

        // Nothing owed, nothing subscribed: the allocation is unlocked, so the tier really
        // produces the lines this test compares against.
        when(monthlyPaymentRepository.findAll()).thenReturn(List.of());
        when(transactionRepository.sumByTypeCurrencyDateRange(any(), any(), any(), any())).thenReturn(null);
        when(transactionRepository.sumBySubTypeCurrencyDateRange(any(), any(), any(), any())).thenReturn(null);
        when(transactionRepository.sumBonusIncomeByCurrencyDateRange(any(), any(), any())).thenReturn(null);
        when(transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                any(), any(), any())).thenReturn(List.of());
        when(markPaidRepository.findByKindAndRefIdAndMonth(any(), any(), any())).thenReturn(List.of());
        when(markPaidRepository.findByMonth(any())).thenReturn(List.of());
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(any(), any()))
                .thenReturn(List.of());
        when(investmentRepository.findById(any())).thenReturn(Optional.empty());
        when(investmentRepository.findByOriginatingTransactionId(any())).thenReturn(Optional.empty());

        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        LevelAllocationRuleRepository ruleRepository = mock(LevelAllocationRuleRepository.class);
        LevelConfigRepository levelConfigRepository = mock(LevelConfigRepository.class);
        when(bankLoanRepository.findAll()).thenReturn(List.of());
        when(loanTakenRepository.findAll()).thenReturn(List.of());
        when(debtRepository.findAll()).thenReturn(List.of());
        when(levelConfigRepository.findByLevel(anyInt())).thenReturn(Optional.empty());
        when(ruleRepository.findBySubLevel(any())).thenReturn(Optional.empty());

        overviewService = new OverviewService(
                transactionRepository, monthlyPaymentRepository, bankLoanRepository,
                loanTakenRepository, debtRepository, donationRepository, investmentRepository,
                ruleRepository, levelConfigRepository, markPaidRepository, settingsService);

        // One card, no cash pot — the smallest wallet set that still exercises the close.
        Card card = new Card();
        card.setId(1L);
        card.setName("Main card");
        card.setCurrency(Currency.UZS);
        card.setInitialBalance(BigDecimal.ZERO);
        when(cardRepository.findAll()).thenReturn(List.of(card));
        when(cardRepository.sumTransactionsByCardIdUpTo(1L, MONTH_END)).thenReturn(COMPUTED_AT_MONTH_END);
        when(cardRepository.sumTransactionsByCardIdUpTo(1L, PRIOR_END)).thenReturn(START_BALANCE);
        when(cashBalanceRepository.sumCashlessTransactionsUpTo(any(), any())).thenReturn(BigDecimal.ZERO);
        when(cashBalanceRepository.findByCurrency(any())).thenReturn(Optional.empty());

        when(monthCloseRepository.existsByMonth(any())).thenReturn(false);
        when(monthCloseRepository.findByMonth(any())).thenReturn(Optional.empty());
        when(monthCloseRepository.findTopByOrderByMonthDesc()).thenReturn(Optional.empty());
        when(monthCloseRepository.save(any(MonthClose.class))).thenAnswer(i -> i.getArgument(0));
        when(categoryRepository.findByApplicableSubTypeAndParentIsNull(any())).thenReturn(List.of());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            t.setId(900L);
            return t;
        });

        monthCloseService = new MonthCloseService(
                monthCloseRepository, cardRepository, cashBalanceRepository, transactionRepository,
                categoryRepository, overviewService, settingsService);
    }

    /**
     * Every bucket funded once, five different ways, plus two marks. Deliberately non-round and
     * all different, so a figure that lands in the wrong bucket cannot pass by coincidence.
     */
    private void givenAFullyFundedMonth() {
        Donation d = new Donation();
        d.setId(1L);
        d.setRecipientName("Mosque");
        d.setAmount(DONATED);
        d.setCurrency(Currency.UZS);
        d.setDonationDate(MONTH.atDay(15));
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(MONTH_START, MONTH_END))
                .thenReturn(List.of(d));

        when(transactionRepository.sumBySubTypeCurrencyDateRange(
                eq(TransactionSubType.EMERGENCY_CONTRIBUTION), eq(Currency.UZS), eq(MONTH_START), eq(MONTH_END)))
                .thenReturn(EMERGENCY);
        when(transactionRepository.sumBySubTypeCurrencyDateRange(
                eq(TransactionSubType.STOCK_PURCHASE), eq(Currency.UZS), eq(MONTH_START), eq(MONTH_END)))
                .thenReturn(STOCKS);
        when(transactionRepository.sumByTypeCurrencyDateRange(
                eq(TransactionType.INCOME), eq(Currency.UZS), eq(MONTH_START), eq(MONTH_END)))
                .thenReturn(INCOME);

        // Investments and Savings share one sub-type; the target's savingsGoal flag splits them.
        Transaction toHolding = investmentTx(10L, 7L, INVESTED);
        Transaction toGoal = investmentTx(11L, 8L, SAVED);
        when(transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                eq(TransactionSubType.INVESTMENT), eq(MONTH_START), eq(MONTH_END)))
                .thenReturn(List.of(toHolding, toGoal));
        when(investmentRepository.findById(7L)).thenReturn(Optional.of(investment(7L, false)));
        when(investmentRepository.findById(8L)).thenReturn(Optional.of(investment(8L, true)));

        when(markPaidRepository.findByMonth(MONTH_START)).thenReturn(List.of(
                bucketMark(90L, "DONATION", DONATION_MARK),
                bucketMark(91L, "STOCKS", STOCKS_MARK)));
    }

    private Transaction investmentTx(long id, Long investmentId, BigDecimal amount) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setSubType(TransactionSubType.INVESTMENT);
        t.setInvestmentId(investmentId);
        t.setAmount(amount);
        t.setCurrency(Currency.UZS);
        t.setTransactionDate(MONTH.atDay(10));
        return t;
    }

    private Investment investment(long id, boolean savingsGoal) {
        Investment i = new Investment();
        i.setId(id);
        i.setName(savingsGoal ? "Car fund" : "Bonds");
        i.setSavingsGoal(savingsGoal);
        i.setInvestedAmount(new BigDecimal("1000"));
        i.setCurrency(Currency.UZS);
        return i;
    }

    private MarkPaid bucketMark(long id, String bucket, BigDecimal amount) {
        MarkPaid m = new MarkPaid();
        m.setId(id);
        m.setKind("BUCKET");
        m.setBucket(bucket);
        m.setMonth(MONTH_START);
        m.setAmount(amount);
        m.setCurrency(Currency.UZS);
        return m;
    }

    private AllocationLine line(OverviewTierResponse tier, String bucket) {
        return tier.getAllocation().getLines().stream()
                .filter(l -> bucket.equals(l.getBucket())).findFirst().orElseThrow();
    }

    private MonthCloseRequest closeRequest(BigDecimal enteredBalance) {
        MonthCloseRequest req = new MonthCloseRequest();
        req.setMonth(MONTH.toString());
        MonthCloseRequest.WalletBalanceEntry w = new MonthCloseRequest.WalletBalanceEntry();
        w.setWalletType("CARD");
        w.setCardId(1L);
        w.setCurrency(Currency.UZS);
        w.setEnteredBalance(enteredBalance);
        req.setWallets(List.of(w));
        return req;
    }

    /**
     * Commit the close and wire the repository so the month reads back as closed — the sequence
     * every "after the user pressed Close" assertion below depends on.
     */
    private MonthClose closeAndReadBack() {
        monthCloseService.close(closeRequest(ENTERED_AT_CLOSE));
        ArgumentCaptor<MonthClose> saved = ArgumentCaptor.forClass(MonthClose.class);
        org.mockito.Mockito.verify(monthCloseRepository).save(saved.capture());
        MonthClose snapshot = saved.getValue();
        when(monthCloseRepository.findByMonth(MONTH_START)).thenReturn(Optional.of(snapshot));
        return snapshot;
    }

    /**
     * The finding itself: the Plan said "Paid 284,2 k · target met" for the exact month the
     * Months page called 50.000. Both sides now come from one call with one includeMarks value,
     * so they cannot drift again without this failing.
     */
    @Test
    void thePlanAndTheMonthsPageQuoteOneFigurePerBucket() {
        givenAFullyFundedMonth();

        OverviewTierResponse tier = overviewService.getTier(MONTH, Currency.UZS);
        MonthSummaryResponse summary = monthCloseService.getMonthSummary(MONTH, Currency.UZS);

        assertThat(summary.isClosed()).isFalse();
        assertThat(summary.getDonation()).isEqualByComparingTo(line(tier, "DONATION").getPaidAmount());
        assertThat(summary.getEmergency()).isEqualByComparingTo(line(tier, "EMERGENCY").getPaidAmount());
        assertThat(summary.getInvestments()).isEqualByComparingTo(line(tier, "INVESTMENTS").getPaidAmount());

        // The marked share is named identically on both, so each page can label which half moved money.
        assertThat(summary.getMarkedDonation()).isEqualByComparingTo(line(tier, "DONATION").getMarkedAmount());
        assertThat(summary.getMarkedEmergency()).isEqualByComparingTo(line(tier, "EMERGENCY").getMarkedAmount());
        assertThat(summary.getMarkedInvestments()).isEqualByComparingTo(line(tier, "INVESTMENTS").getMarkedAmount());

        // The audit's own pair of numbers, spelled out so a regression names itself.
        assertThat(summary.getDonation()).isEqualByComparingTo("284200");
        assertThat(summary.getMarkedDonation()).isEqualByComparingTo(DONATION_MARK);
    }

    /**
     * "Where it went" lists every bucket and then a total: the rows must add up to it, Stocks
     * included. Dropping stocks from taggedTotal (the tempting cleanup now that the bucket is
     * retired) would leave the page showing rows that do not sum — and would silently change
     * everydaySpend for every open month.
     */
    @Test
    void theWhereItWentRowsSumToTheirStatedTotal() {
        givenAFullyFundedMonth();

        MonthSummaryResponse s = monthCloseService.getMonthSummary(MONTH, Currency.UZS);

        assertThat(s.getDonation().add(s.getEmergency()).add(s.getInvestments())
                .add(s.getStocks()).add(s.getSavings()))
                .isEqualByComparingTo(s.getTaggedTotal());
        assertThat(s.getTaggedTotal()).isEqualByComparingTo(RECORDED_TOTAL.add(MARKED_TOTAL));

        // The recorded half is stated outright, not left to the page to derive — and it is exactly
        // what the close will freeze.
        assertThat(s.getTaggedRecorded()).isEqualByComparingTo(RECORDED_TOTAL);
        assertThat(s.getTaggedRecorded().add(s.getMarkedNotMoved())).isEqualByComparingTo(s.getTaggedTotal());

        // markedNotMoved counts the STOCKS mark, which has no marked* field of its own — so it is
        // deliberately larger than the three named ones and must not be presented as their sum.
        assertThat(s.getMarkedNotMoved()).isEqualByComparingTo(MARKED_TOTAL);
        assertThat(s.getMarkedNotMoved()).isGreaterThan(
                s.getMarkedDonation().add(s.getMarkedEmergency()).add(s.getMarkedInvestments()));
    }

    /** The dialog the user commits from must quote the plan's figures, not a third set. */
    @Test
    void theClosePreviewQuotesThePlansFigureAndNamesWhatWillNotSurvive() {
        givenAFullyFundedMonth();

        OverviewTierResponse tier = overviewService.getTier(MONTH, Currency.UZS);
        MonthClosePreviewResponse p = monthCloseService.preview(MONTH, Currency.UZS);

        assertThat(p.isCloseable()).isTrue();
        assertThat(p.getDonation()).isEqualByComparingTo(line(tier, "DONATION").getPaidAmount());
        assertThat(p.getStocks()).isEqualByComparingTo(STOCKS.add(STOCKS_MARK));
        assertThat(p.getTaggedTotal()).isEqualByComparingTo(RECORDED_TOTAL.add(MARKED_TOTAL));
        assertThat(p.getMarkedNotMoved()).isEqualByComparingTo(MARKED_TOTAL);
        assertThat(p.getTaggedRecorded()).isEqualByComparingTo(RECORDED_TOTAL);
        assertThat(p.getTaggedRecorded().add(p.getMarkedNotMoved())).isEqualByComparingTo(p.getTaggedTotal());
    }

    /**
     * The snapshot is permanent and cannot be reopened, so it freezes the recorded half alone.
     * Counting a mark there would shrink everydaySpend by money that never left a wallet —
     * for ever. Closing and reading the month back must still balance.
     */
    @Test
    void theCloseFreezesTheRecordedHalfAndTheSnapshotStillBalances() {
        givenAFullyFundedMonth();

        MonthClosePreviewResponse preview = monthCloseService.preview(MONTH, Currency.UZS);
        BigDecimal willFreeze = preview.getTaggedRecorded();

        MonthClose snapshot = closeAndReadBack();

        // Recorded only: the 234.200 donation mark and the 15.000 stocks mark are absent.
        assertThat(snapshot.getDonationUzs()).isEqualByComparingTo(DONATED);
        assertThat(snapshot.getStocksUzs()).isEqualByComparingTo(STOCKS);
        assertThat(snapshot.getEmergencyUzs()).isEqualByComparingTo(EMERGENCY);
        assertThat(snapshot.getInvestmentsUzs()).isEqualByComparingTo(INVESTED);
        assertThat(snapshot.getSavingsUzs()).isEqualByComparingTo(SAVED);

        BigDecimal tagged = snapshot.getDonationUzs().add(snapshot.getEmergencyUzs())
                .add(snapshot.getInvestmentsUzs()).add(snapshot.getStocksUzs()).add(snapshot.getSavingsUzs());
        assertThat(tagged).isEqualByComparingTo(RECORDED_TOTAL);
        assertThat(tagged).isEqualByComparingTo(willFreeze); // the preview promised exactly this

        // totalSpent = start + income − leftover, and everydaySpend is what is left after the buckets.
        assertThat(snapshot.getTotalSpentUzs())
                .isEqualByComparingTo(START_BALANCE.add(INCOME).subtract(ENTERED_AT_CLOSE));
        assertThat(snapshot.getEverydaySpendUzs())
                .isEqualByComparingTo(snapshot.getTotalSpentUzs().subtract(tagged));
    }

    /**
     * The regression this run exists to kill. Making the OPEN month agree with the Plan fixed
     * nothing the moment the user pressed Close: the frozen snapshot is recorded-only, so Home and
     * Plan went on saying "Paid 284,2 k · Done" for September while Months said "Set aside 50.000".
     * Reading a closed month now adds the same immutable marks on top of the same frozen columns,
     * so all five screens quote one figure — and the stored row is still never rewritten.
     */
    @Test
    void aClosedMonthStillQuotesThePlansFigurePerBucket() {
        givenAFullyFundedMonth();
        MonthClose snapshot = closeAndReadBack();

        OverviewTierResponse tier = overviewService.getTier(MONTH, Currency.UZS);
        MonthSummaryResponse closed = monthCloseService.getMonthSummary(MONTH, Currency.UZS);

        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getDonation()).isEqualByComparingTo(line(tier, "DONATION").getPaidAmount());
        assertThat(closed.getEmergency()).isEqualByComparingTo(line(tier, "EMERGENCY").getPaidAmount());
        assertThat(closed.getInvestments()).isEqualByComparingTo(line(tier, "INVESTMENTS").getPaidAmount());

        // The audit's own pair of numbers, spelled out so a regression names itself.
        assertThat(closed.getDonation()).isEqualByComparingTo("284200");
        assertThat(closed.getStocks()).isEqualByComparingTo(STOCKS.add(STOCKS_MARK));
        assertThat(closed.getTaggedTotal()).isEqualByComparingTo(RECORDED_TOTAL.add(MARKED_TOTAL));

        // Achieved by reading, not by rewriting: the immutable row still holds the recorded half.
        assertThat(snapshot.getDonationUzs()).isEqualByComparingTo(DONATED);
        assertThat(snapshot.getStocksUzs()).isEqualByComparingTo(STOCKS);
    }

    /**
     * Closing is a bookkeeping act, not a revaluation. Every figure the Months page prints must
     * survive it unchanged — otherwise the close button itself becomes the trigger for the
     * contradiction, which is exactly how the bug came back last time.
     */
    @Test
    void closingAMonthChangesNoFigureTheUserIsLookingAt() {
        givenAFullyFundedMonth();

        MonthSummaryResponse before = monthCloseService.getMonthSummary(MONTH, Currency.UZS);
        closeAndReadBack();
        MonthSummaryResponse after = monthCloseService.getMonthSummary(MONTH, Currency.UZS);

        assertThat(before.isClosed()).isFalse();
        assertThat(after.isClosed()).isTrue();

        assertThat(after.getDonation()).isEqualByComparingTo(before.getDonation());
        assertThat(after.getEmergency()).isEqualByComparingTo(before.getEmergency());
        assertThat(after.getInvestments()).isEqualByComparingTo(before.getInvestments());
        assertThat(after.getStocks()).isEqualByComparingTo(before.getStocks());
        assertThat(after.getSavings()).isEqualByComparingTo(before.getSavings());
        assertThat(after.getTaggedTotal()).isEqualByComparingTo(before.getTaggedTotal());
        assertThat(after.getTaggedRecorded()).isEqualByComparingTo(before.getTaggedRecorded());
        assertThat(after.getMarkedNotMoved()).isEqualByComparingTo(before.getMarkedNotMoved());
        assertThat(after.getStartBalance()).isEqualByComparingTo(before.getStartBalance());
        assertThat(after.getIncome()).isEqualByComparingTo(before.getIncome());

        // Only the three figures that are genuinely unknowable before a close appear.
        assertThat(before.getEverydaySpend()).isNull();
        assertThat(before.getTotalSpent()).isNull();
        assertThat(before.getLeftover()).isNull();
        assertThat(after.getEverydaySpend()).isNotNull();
        assertThat(after.getTotalSpent()).isNotNull();
        assertThat(after.getLeftover()).isEqualByComparingTo(ENTERED_AT_CLOSE);
    }

    /**
     * The other half of the contract: quoting the plan's figure must not corrupt the envelope
     * arithmetic. everydaySpend is the frozen column, replayed byte for byte, and it balances
     * against the RECORDED half — counting the 249.200 of marks there would have deleted that much
     * everyday spending from a month that can never be reopened to correct it.
     */
    @Test
    void aClosedMonthsArithmeticStillBalancesOnTheRecordedHalfAlone() {
        givenAFullyFundedMonth();
        MonthClose snapshot = closeAndReadBack();

        MonthSummaryResponse closed = monthCloseService.getMonthSummary(MONTH, Currency.UZS);

        // The five rows still add up to the total printed under them.
        assertThat(closed.getDonation().add(closed.getEmergency()).add(closed.getInvestments())
                .add(closed.getStocks()).add(closed.getSavings()))
                .isEqualByComparingTo(closed.getTaggedTotal());

        // And that total splits cleanly into the half that moved money and the half that did not.
        assertThat(closed.getTaggedRecorded()).isEqualByComparingTo(RECORDED_TOTAL);
        assertThat(closed.getMarkedNotMoved()).isEqualByComparingTo(MARKED_TOTAL);
        assertThat(closed.getTaggedRecorded().add(closed.getMarkedNotMoved()))
                .isEqualByComparingTo(closed.getTaggedTotal());

        assertThat(closed.getEverydaySpend()).isEqualByComparingTo(snapshot.getEverydaySpendUzs());
        assertThat(closed.getTotalSpent()).isEqualByComparingTo(snapshot.getTotalSpentUzs());
        assertThat(closed.getTotalSpent().subtract(closed.getTaggedRecorded()))
                .isEqualByComparingTo(closed.getEverydaySpend());
    }

    /** With no marks at all the two figures are the same number, and nothing is "not moved". */
    @Test
    void withoutMarksTheOpenMonthAndTheCloseAgreeExactly() {
        givenAFullyFundedMonth();
        when(markPaidRepository.findByMonth(MONTH_START)).thenReturn(new ArrayList<>());

        MonthSummaryResponse s = monthCloseService.getMonthSummary(MONTH, Currency.UZS);
        assertThat(s.getMarkedNotMoved()).isEqualByComparingTo("0");
        assertThat(s.getTaggedTotal()).isEqualByComparingTo(RECORDED_TOTAL);
        assertThat(s.getTaggedRecorded()).isEqualByComparingTo(s.getTaggedTotal());

        MonthClose snapshot = closeAndReadBack();
        assertThat(snapshot.getDonationUzs()).isEqualByComparingTo(s.getDonation());
        assertThat(snapshot.getStocksUzs()).isEqualByComparingTo(s.getStocks());

        // With nothing merely marked, the two totals collapse into one on the closed month too.
        MonthSummaryResponse closed = monthCloseService.getMonthSummary(MONTH, Currency.UZS);
        assertThat(closed.getTaggedTotal()).isEqualByComparingTo(RECORDED_TOTAL);
        assertThat(closed.getTaggedRecorded()).isEqualByComparingTo(RECORDED_TOTAL);
        assertThat(closed.getDonation()).isEqualByComparingTo(DONATED);
    }
}
