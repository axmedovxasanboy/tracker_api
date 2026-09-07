package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.BucketPayment;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine;
import uz.tracker.trackerproject.entity.Donation;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.entity.MonthlyPayment;
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
 * An "already paid" mark and a recorded payment are two halves of ONE bucket total, and every
 * screen must quote that same total. Before this, the tier and the ledger folded marks in while
 * the month summary, the close preview and the bucket-history panel did not — so the Overview
 * said "Paid 284,2 k · target met" for the exact month the Months page called 50.000.
 *
 * <p>The one place that must still see only the recorded half is the irreversible close snapshot:
 * a mark moves no tracked money, so counting it there would shrink everydaySpend by money that
 * never left a wallet, permanently.
 */
class MarkPaidAccountingTest {

    private TransactionRepository transactionRepository;
    private DonationRepository donationRepository;
    private MarkPaidRepository markPaidRepository;
    private MonthlyPaymentRepository monthlyPaymentRepository;
    private OverviewService service;

    private static final YearMonth AUG = YearMonth.of(2026, 8);
    /** The audit's own figures: 50.000 recorded, 234.200 merely marked. */
    private static final BigDecimal RECORDED = new BigDecimal("50000");
    private static final BigDecimal MARKED = new BigDecimal("234200");

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        donationRepository = mock(DonationRepository.class);
        markPaidRepository = mock(MarkPaidRepository.class);
        monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
        LevelAllocationRuleRepository ruleRepository = mock(LevelAllocationRuleRepository.class);
        LevelConfigRepository levelConfigRepository = mock(LevelConfigRepository.class);
        SettingsService settingsService = mock(SettingsService.class);

        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("10000000")); // Level 1, no debt → 10/5/15
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(AUG.atDay(1));      // ledger iterates exactly AUG
        when(settingsService.getOrCreate()).thenReturn(settings);

        when(monthlyPaymentRepository.findAll()).thenReturn(List.of());
        when(bankLoanRepository.findAll()).thenReturn(List.of());
        when(loanTakenRepository.findAll()).thenReturn(List.of());
        when(debtRepository.findAll()).thenReturn(List.of());
        when(levelConfigRepository.findByLevel(anyInt())).thenReturn(Optional.empty());
        when(ruleRepository.findBySubLevel(any())).thenReturn(Optional.empty());
        when(transactionRepository.sumByTypeCurrencyDateRange(any(), any(), any(), any())).thenReturn(null);
        when(transactionRepository.sumBySubTypeCurrencyDateRange(any(), any(), any(), any())).thenReturn(null);
        when(transactionRepository.sumBonusIncomeByCurrencyDateRange(any(), any(), any())).thenReturn(null);
        when(transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                any(), any(), any())).thenReturn(List.of());
        when(markPaidRepository.findByKindAndRefIdAndMonth(any(), any(), any())).thenReturn(List.of());
        when(markPaidRepository.findByMonth(any())).thenReturn(List.of());
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(any(), any()))
                .thenReturn(List.of());

        service = new OverviewService(
                transactionRepository, monthlyPaymentRepository, bankLoanRepository,
                loanTakenRepository, debtRepository, donationRepository, investmentRepository,
                ruleRepository, levelConfigRepository, markPaidRepository, settingsService);
    }

    /** 50.000 actually donated in August, 234.200 declared "already paid" for the same month. */
    private void givenARecordedDonationAndAMark() {
        Donation d = new Donation();
        d.setId(1L);
        d.setRecipientName("Mosque");
        d.setAmount(RECORDED);
        d.setCurrency(Currency.UZS);
        d.setDonationDate(LocalDate.of(2026, 8, 15));
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(
                AUG.atDay(1), AUG.atEndOfMonth())).thenReturn(List.of(d));

        MarkPaid m = new MarkPaid();
        m.setId(90L);
        m.setKind("BUCKET");
        m.setBucket("DONATION");
        m.setMonth(AUG.atDay(1));
        m.setAmount(MARKED);
        m.setCurrency(Currency.UZS);
        m.setNote("Paid in cash at the mosque");
        when(markPaidRepository.findByMonth(AUG.atDay(1))).thenReturn(List.of(m));
    }

    private AllocationLine donationLine(OverviewTierResponse tier) {
        return tier.getAllocation().getLines().stream()
                .filter(l -> "DONATION".equals(l.getBucket())).findFirst().orElseThrow();
    }

    @Test
    void theTierAndTheLedgerQuoteTheSamePaidFigureAndNameTheMarkedShare() {
        givenARecordedDonationAndAMark();

        AllocationLine line = donationLine(service.getTier(AUG, Currency.UZS));
        assertThat(line.getPaidAmount()).isEqualByComparingTo("284200");
        assertThat(line.getMarkedAmount()).isEqualByComparingTo(MARKED);

        AllocationLedgerResponse ledger = service.getAllocationLedger(AUG, Currency.UZS);
        AllocationLedgerResponse.BucketLedger bucket = ledger.getBuckets().stream()
                .filter(b -> "DONATION".equals(b.getBucket())).findFirst().orElseThrow();
        assertThat(bucket.getPaid()).isEqualByComparingTo("284200");
        assertThat(bucket.getMarked()).isEqualByComparingTo(MARKED);
    }

    /**
     * The month view and the close preview read the marks-inclusive figure (that is what makes
     * them agree with the plan); the close snapshot reads the recorded figure alone, because
     * everydaySpend = totalSpent − taggedTotal only balances against real wallet movement.
     */
    @Test
    void onlyTheCloseSnapshotSeesTheRecordedHalf() {
        givenARecordedDonationAndAMark();

        assertThat(service.computePaidThisMonth(AUG, Currency.UZS, true).donation())
                .isEqualByComparingTo("284200");
        assertThat(service.computePaidThisMonth(AUG, Currency.UZS, false).donation())
                .isEqualByComparingTo(RECORDED);
        assertThat(service.computeBucketMarks(AUG).donation()).isEqualByComparingTo(MARKED);
        assertThat(OverviewService.markedTotal(service.computeBucketMarks(AUG)))
                .isEqualByComparingTo(MARKED);
    }

    /** The history panel must add up to the card above it, so the mark is a row in it. */
    @Test
    void theBucketHistoryListsTheMarkAsItsOwnRow() {
        givenARecordedDonationAndAMark();

        List<BucketPayment> rows = service.getBucketPayments("DONATION", AUG, Currency.UZS);

        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(BucketPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("284200");
        BucketPayment mark = rows.stream().filter(BucketPayment::isMarked).findFirst().orElseThrow();
        assertThat(mark.getId()).isEqualTo(90L);            // a MarkPaid id, not a Donation id
        assertThat(mark.getAmount()).isEqualByComparingTo(MARKED);
        assertThat(mark.getDescription()).isEqualTo("Paid in cash at the mosque");
    }

    /**
     * The tier withholds its whole allocation until the month's mandatory subscriptions are paid.
     * The ledger used to ignore that gate entirely, so the header printed a concrete monthly ask
     * directly above the cards saying guidance was unavailable.
     */
    @Test
    void theLedgerWithholdsItsDuesWhileSubscriptionsArePending() {
        MonthlyPayment sub = new MonthlyPayment();
        sub.setId(5L);
        sub.setName("Internet");
        sub.setAmount(new BigDecimal("300000"));
        sub.setCurrency(Currency.UZS);
        sub.setActive(true);
        when(monthlyPaymentRepository.findAll()).thenReturn(List.of(sub));
        when(transactionRepository.sumByMonthlyPaymentIdAndDateRange(any(), any(), any())).thenReturn(null);

        OverviewTierResponse tier = service.getTier(AUG, Currency.UZS);
        assertThat(tier.isSubscriptionsPending()).isTrue();
        assertThat(tier.getAllocation().getLines()).isEmpty();

        AllocationLedgerResponse ledger = service.getAllocationLedger(AUG, Currency.UZS);
        assertThat(ledger.isSubscriptionsPending()).isTrue();
        assertThat(ledger.getBuckets()).isEmpty();
        assertThat(ledger.getMonths()).isEmpty();
        assertThat(ledger.getDueThisMonth()).isNull();
        assertThat(ledger.getTotalDueNow()).isNull();
    }
}
