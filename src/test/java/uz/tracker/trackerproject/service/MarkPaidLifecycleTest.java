package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.DonationRequest;
import uz.tracker.trackerproject.dto.request.MarkPaidRequest;
import uz.tracker.trackerproject.dto.response.BucketPayment;
import uz.tracker.trackerproject.dto.response.MarkPaidResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine;
import uz.tracker.trackerproject.entity.Donation;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mark used to be write-only: five modals could create one and nothing could list or remove
 * it, so a mistyped amount inflated the allocation for ever with no screen able to show why.
 * These pin the full round trip — created, found, undone — and pin that undoing it puts every
 * figure back exactly where it was.
 *
 * <p>The {@link FinanceService} that writes marks and the {@link OverviewService} that reads
 * them share one in-memory store here, so "the figure goes back" is measured on the real
 * allocation output rather than on the repository call.
 *
 * <p>The same wiring pins the closed-month lock over both halves of a bucket total — the marks
 * and the Donation rows. A closed month reports frozen columns plus live marks, so it reads the
 * same figure as the plan only for as long as neither half can still be changed underneath it.
 */
class MarkPaidLifecycleTest {

    private MarkPaidRepository markPaidRepository;
    private DonationRepository donationRepository;
    private LoanTakenRepository loanTakenRepository;
    private MonthCloseService monthCloseService;
    private FinanceService financeService;
    private OverviewService overviewService;

    /** Every mark the fake repository holds, in insertion order. */
    private final List<MarkPaid> stored = new ArrayList<>();
    private long nextId = 90L;

    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final BigDecimal RECORDED = new BigDecimal("50000");
    private static final BigDecimal MARKED = new BigDecimal("234200");

    @BeforeEach
    void setUp() {
        markPaidRepository = mock(MarkPaidRepository.class);
        donationRepository = mock(DonationRepository.class);
        loanTakenRepository = mock(LoanTakenRepository.class);
        monthCloseService = mock(MonthCloseService.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
        LevelAllocationRuleRepository ruleRepository = mock(LevelAllocationRuleRepository.class);
        LevelConfigRepository levelConfigRepository = mock(LevelConfigRepository.class);
        SettingsService settingsService = mock(SettingsService.class);

        givenAnInMemoryMarkStore();

        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("10000000")); // Level 1, no debt
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(AUG.atDay(1));
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
        when(investmentRepository.findById(any())).thenReturn(Optional.empty());
        when(investmentRepository.findByOriginatingTransactionId(any())).thenReturn(Optional.empty());

        // 50.000 really donated in August — the recorded half the marks are added to.
        Donation d = new Donation();
        d.setId(1L);
        d.setRecipientName("Mosque");
        d.setAmount(RECORDED);
        d.setCurrency(Currency.UZS);
        d.setDonationDate(AUG.atDay(15));
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(any(), any()))
                .thenReturn(List.of());
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(
                AUG.atDay(1), AUG.atEndOfMonth())).thenReturn(List.of(d));

        overviewService = new OverviewService(
                transactionRepository, monthlyPaymentRepository, bankLoanRepository,
                loanTakenRepository, debtRepository, donationRepository, investmentRepository,
                ruleRepository, levelConfigRepository, markPaidRepository, settingsService);

        financeService = new FinanceService(
                debtRepository,
                mock(LoanGivenRepository.class),
                loanTakenRepository,
                bankLoanRepository,
                monthlyPaymentRepository,
                donationRepository,
                investmentRepository,
                mock(CategoryRepository.class),
                transactionRepository,
                mock(CardRepository.class),
                markPaidRepository,
                mock(CardService.class),
                monthCloseService,
                settingsService);
    }

    /** A minimal repository that actually remembers, so a delete is observable downstream. */
    private void givenAnInMemoryMarkStore() {
        when(markPaidRepository.save(any(MarkPaid.class))).thenAnswer(i -> {
            MarkPaid m = i.getArgument(0);
            if (m.getId() == null) m.setId(nextId++);
            stored.removeIf(x -> m.getId().equals(x.getId()));
            stored.add(m);
            return m;
        });
        when(markPaidRepository.findById(any())).thenAnswer(i ->
                stored.stream().filter(m -> m.getId().equals(i.getArgument(0))).findFirst());
        when(markPaidRepository.findByMonth(any())).thenAnswer(i ->
                stored.stream().filter(m -> m.getMonth().equals(i.getArgument(0))).toList());
        when(markPaidRepository.findByMonthOrderByIdDesc(any())).thenAnswer(i ->
                stored.stream().filter(m -> m.getMonth().equals(i.getArgument(0)))
                        .sorted(Comparator.comparing(MarkPaid::getId).reversed()).toList());
        when(markPaidRepository.findByKindAndRefIdAndMonth(any(), any(), any())).thenAnswer(i ->
                stored.stream()
                        .filter(m -> m.getKind().equals(i.getArgument(0))
                                && java.util.Objects.equals(m.getRefId(), i.getArgument(1))
                                && m.getMonth().equals(i.getArgument(2)))
                        .toList());
        doAnswer(i -> {
            stored.remove((MarkPaid) i.getArgument(0));
            return null;
        }).when(markPaidRepository).delete(any(MarkPaid.class));
    }

    private MarkPaidRequest bucketMark(String bucket, BigDecimal amount, String note) {
        MarkPaidRequest req = new MarkPaidRequest();
        req.setKind("BUCKET");
        req.setBucket(bucket);
        req.setMonth(AUG.toString());
        req.setAmount(amount);
        req.setCurrency(Currency.UZS);
        req.setNote(note);
        return req;
    }

    private AllocationLine donationLine() {
        OverviewTierResponse tier = overviewService.getTier(AUG, Currency.UZS);
        return tier.getAllocation().getLines().stream()
                .filter(l -> "DONATION".equals(l.getBucket())).findFirst().orElseThrow();
    }

    /**
     * The whole point of listing marks: the user can find the 234.200 that made the plan disagree
     * with the month view, and removing it puts the plan back to the 50.000 that really moved.
     */
    @Test
    void aBucketMarkIsListedThenUndone_andEveryFigureGoesBack() {
        assertThat(financeService.listMarks(AUG.toString())).isEmpty();
        assertThat(donationLine().getPaidAmount()).isEqualByComparingTo(RECORDED);

        MarkPaidResponse created = financeService.markPaid(
                bucketMark("DONATION", MARKED, "Paid in cash at the mosque"));

        List<MarkPaidResponse> listed = financeService.listMarks(AUG.toString());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).getId()).isEqualTo(created.getId());
        assertThat(listed.get(0).getBucket()).isEqualTo("DONATION");
        assertThat(listed.get(0).getAmount()).isEqualByComparingTo(MARKED);
        assertThat(listed.get(0).getNote()).isEqualTo("Paid in cash at the mosque");

        assertThat(donationLine().getPaidAmount()).isEqualByComparingTo("284200");
        assertThat(donationLine().getMarkedAmount()).isEqualByComparingTo(MARKED);
        assertThat(overviewService.getBucketPayments("DONATION", AUG, Currency.UZS)).hasSize(2);

        financeService.deleteMark(created.getId());

        assertThat(financeService.listMarks(AUG.toString())).isEmpty();
        assertThat(donationLine().getPaidAmount()).isEqualByComparingTo(RECORDED);
        assertThat(donationLine().getMarkedAmount()).isEqualByComparingTo("0");
        List<BucketPayment> rows = overviewService.getBucketPayments("DONATION", AUG, Currency.UZS);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isMarked()).isFalse();
    }

    /**
     * A PERSONAL_LOAN mark mirrors a real repayment by bumping the loan's paidAmount, so undoing
     * it has to back that bump out — otherwise the balance stays understated for ever and the
     * 34% monthly charge shrinks with it.
     */
    @Test
    void undoingALoanMarkBacksOutThePaidBumpItApplied() {
        LoanTaken loan = new LoanTaken();
        loan.setId(3L);
        loan.setLenderName("Brother");
        loan.setTotalAmount(new BigDecimal("1000000"));
        loan.setPaidAmount(BigDecimal.ZERO);
        loan.setCurrency(Currency.UZS);
        loan.setStatus(RecordStatus.PENDING);
        when(loanTakenRepository.findById(3L)).thenReturn(Optional.of(loan));

        MarkPaidRequest req = new MarkPaidRequest();
        req.setKind("PERSONAL_LOAN");
        req.setRefId(3L);
        req.setMonth(AUG.toString());
        req.setAmount(new BigDecimal("340000"));
        req.setCurrency(Currency.UZS);
        MarkPaidResponse created = financeService.markPaid(req);

        assertThat(loan.getPaidAmount()).isEqualByComparingTo("340000");
        assertThat(loan.getStatus()).isEqualTo(RecordStatus.PARTIALLY_PAID);

        financeService.deleteMark(created.getId());

        assertThat(loan.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(loan.getStatus()).isEqualTo(RecordStatus.PENDING);
        assertThat(financeService.listMarks(AUG.toString())).isEmpty();
    }

    /**
     * A closed month is permanent. Its snapshot never counted the mark, but its tier and ledger
     * history did — so removing one after the fact would rewrite a month the user can no longer
     * reopen.
     */
    @Test
    void aMarkInsideAClosedMonthCannotBeUndone() {
        MarkPaidResponse created = financeService.markPaid(bucketMark("DONATION", MARKED, null));
        doThrow(new IllegalArgumentException("The month " + AUG + " is closed and locked"))
                .when(monthCloseService).assertMonthOpen(any(LocalDate.class));

        assertThatThrownBy(() -> financeService.deleteMark(created.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closed");

        assertThat(stored).hasSize(1);
        assertThat(donationLine().getPaidAmount()).isEqualByComparingTo("284200");
    }

    /**
     * The other half of a closed month's recorded total. A closed month reports its FROZEN Donation
     * column while the plan re-sums the Donation rows live, so those two agree only for as long as
     * the rows cannot change. createDonation was already gated (through createBucketTransaction);
     * editing and deleting were not, which left the one route by which a closed month could still
     * be made to read two different figures.
     */
    @Test
    void aDonationInsideAClosedMonthCanBeNeitherEditedNorDeleted() {
        Donation august = new Donation();
        august.setId(1L);
        august.setRecipientName("Mosque");
        august.setAmount(RECORDED);
        august.setCurrency(Currency.UZS);
        august.setDonationDate(AUG.atDay(15));
        when(donationRepository.findById(1L)).thenReturn(Optional.of(august));
        // A working save, so that WITHOUT the gate this edit succeeds and rewrites the amount —
        // the test then fails on "no exception", not on an incidental NPE from an unstubbed mock.
        when(donationRepository.save(any(Donation.class))).thenAnswer(i -> i.getArgument(0));
        doThrow(new IllegalArgumentException("The month " + AUG + " is closed and locked"))
                .when(monthCloseService).assertMonthOpen(any(LocalDate.class));

        DonationRequest edit = new DonationRequest();
        edit.setRecipientName("Mosque");
        edit.setAmount(new BigDecimal("999999"));
        edit.setCurrency(Currency.UZS);
        edit.setDonationDate(AUG.atDay(15));

        assertThatThrownBy(() -> financeService.updateDonation(1L, edit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> financeService.deleteDonation(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closed");

        // Nothing was written on the way to the exception, so the month still sums to what it froze.
        assertThat(august.getAmount()).isEqualByComparingTo(RECORDED);
        org.mockito.Mockito.verify(donationRepository, org.mockito.Mockito.never()).save(any(Donation.class));
        org.mockito.Mockito.verify(donationRepository, org.mockito.Mockito.never()).delete(any(Donation.class));
        assertThat(donationLine().getPaidAmount()).isEqualByComparingTo(RECORDED);
    }

    /** Newest first, and scoped to the month asked for — the order the undo list is read in. */
    @Test
    void marksAreListedNewestFirstAndOnlyForTheMonthAskedFor() {
        financeService.markPaid(bucketMark("DONATION", new BigDecimal("10000"), "first"));
        financeService.markPaid(bucketMark("EMERGENCY", new BigDecimal("20000"), "second"));

        MarkPaidRequest julyMark = bucketMark("DONATION", new BigDecimal("30000"), "last month");
        julyMark.setMonth(AUG.minusMonths(1).toString());
        financeService.markPaid(julyMark);

        List<MarkPaidResponse> august = financeService.listMarks(AUG.toString());
        assertThat(august).extracting(MarkPaidResponse::getNote).containsExactly("second", "first");
        assertThat(financeService.listMarks(AUG.minusMonths(1).toString()))
                .extracting(MarkPaidResponse::getNote).containsExactly("last month");
    }
}
