package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.BucketPayment;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A holding's savings-goal / emergency-fund checkboxes must not move money that has already been
 * spent. The split used to be re-derived on every read from the holding's CURRENT flags, while a
 * holding is long-lived and those flags stay editable for ever: ticking "savings goal" on a
 * holding funded in an already-closed August emptied August's Investments bucket on Plan, on the
 * Investments tab and on Home, while Months went on quoting the frozen month-close snapshot.
 * Ticking "emergency fund" did the same in reverse, because it clears savingsGoal.
 *
 * <p>The bucket is now recorded on the funding transaction when the money moves, so the answer
 * cannot change afterwards. These tests read a month whose rows carry a recorded bucket that
 * DISAGREES with the holding's present-day flags — which is exactly the state a later edit leaves
 * behind — and require the recorded value to win.
 */
class ClosedMonthBucketStabilityTest {

    private TransactionRepository transactionRepository;
    private InvestmentRepository investmentRepository;
    private OverviewService service;

    private static final YearMonth AUG = YearMonth.of(2026, 8);

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        investmentRepository = mock(InvestmentRepository.class);
        DonationRepository donationRepository = mock(DonationRepository.class);
        service = new OverviewService(
                transactionRepository,
                mock(MonthlyPaymentRepository.class),
                mock(BankLoanRepository.class),
                mock(LoanTakenRepository.class),
                mock(DebtRepository.class),
                donationRepository,
                investmentRepository,
                mock(LevelAllocationRuleRepository.class),
                mock(LevelConfigRepository.class),
                mock(MarkPaidRepository.class),
                mock(SettingsService.class));
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(any(), any()))
                .thenReturn(List.of());
        when(transactionRepository.sumBySubTypeCurrencyDateRange(any(), any(), any(), any()))
                .thenReturn(null);
        when(transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                any(), any(), any())).thenReturn(List.of());
    }

    /** An INVESTMENT row stamped with the bucket it funded when the money actually left. */
    private Transaction fundingRow(long id, Long investmentId, String amount, String recordedBucket) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setSubType(TransactionSubType.INVESTMENT);
        t.setInvestmentId(investmentId);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency(Currency.UZS);
        t.setTransactionDate(LocalDate.of(2026, 8, 12));
        t.setDescription("Bonds");
        t.setAllocationBucket(recordedBucket);
        return t;
    }

    private Investment holding(long id, boolean savingsGoal) {
        Investment i = new Investment();
        i.setId(id);
        i.setName(savingsGoal ? "Car fund" : "Bonds");
        i.setSavingsGoal(savingsGoal);
        i.setInvestedAmount(new BigDecimal("1000"));
        i.setCurrency(Currency.UZS);
        return i;
    }

    private void givenAugustInvestmentRows(Transaction... txs) {
        when(transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                eq(TransactionSubType.INVESTMENT), eq(AUG.atDay(1)), eq(AUG.atEndOfMonth())))
                .thenReturn(List.of(txs));
    }

    @Test
    void tickingSavingsGoalLaterLeavesAFundedMonthsInvestmentsBucketAlone() {
        // Funded in August as a plain investment; the checkbox was ticked in September, after
        // August had been closed and snapshotted.
        givenAugustInvestmentRows(fundingRow(10L, 7L, "300", "INVESTMENTS"));
        when(investmentRepository.findById(7L)).thenReturn(Optional.of(holding(7L, true)));

        var paid = service.computePaidThisMonth(AUG, Currency.UZS);

        assertThat(paid.investments()).isEqualByComparingTo("300");
        assertThat(paid.savings()).isEqualByComparingTo("0");
    }

    @Test
    void tickingEmergencyFundLaterLeavesAFundedMonthsSavingsBucketAlone() {
        // The reverse direction: it was a savings goal in August, and "emergency fund" — which
        // clears savingsGoal — was ticked afterwards.
        givenAugustInvestmentRows(fundingRow(11L, 8L, "500", "SAVINGS"));
        when(investmentRepository.findById(8L)).thenReturn(Optional.of(holding(8L, false)));

        var paid = service.computePaidThisMonth(AUG, Currency.UZS);

        assertThat(paid.savings()).isEqualByComparingTo("500");
        assertThat(paid.investments()).isEqualByComparingTo("0");
    }

    /**
     * The per-bucket history panel reads the same source as the figure above it. If it re-derived
     * the split, the rows in the drawer would stop adding up to the total on the card — which is
     * the one thing the user opens the drawer to check.
     */
    @Test
    void theBucketHistoryPanelFollowsTheRecordedBucketToo() {
        givenAugustInvestmentRows(
                fundingRow(10L, 7L, "300", "INVESTMENTS"),
                fundingRow(11L, 8L, "500", "SAVINGS"));
        when(investmentRepository.findById(7L)).thenReturn(Optional.of(holding(7L, true)));
        when(investmentRepository.findById(8L)).thenReturn(Optional.of(holding(8L, false)));

        List<BucketPayment> investments = service.getBucketPayments("INVESTMENTS", AUG, Currency.UZS);
        List<BucketPayment> savings = service.getBucketPayments("SAVINGS", AUG, Currency.UZS);

        assertThat(investments).extracting(BucketPayment::getId).containsExactly(10L);
        assertThat(savings).extracting(BucketPayment::getId).containsExactly(11L);
    }

    /**
     * `ddl-auto=update` adds a column but never fills it. DataSeeder back-fills the history on
     * boot, so a null bucket should be unreachable in practice — but the derivation stays as the
     * safety net, and it must still work beside rows that carry a recorded value.
     */
    @Test
    void aRowWrittenBeforeTheColumnExistedStillFallsBackToTheHoldingsFlag() {
        Transaction legacy = fundingRow(12L, 9L, "700", null);
        givenAugustInvestmentRows(fundingRow(10L, 7L, "300", "INVESTMENTS"), legacy);
        when(investmentRepository.findById(7L)).thenReturn(Optional.of(holding(7L, true)));
        when(investmentRepository.findById(9L)).thenReturn(Optional.of(holding(9L, true)));

        var paid = service.computePaidThisMonth(AUG, Currency.UZS);

        // The stamped row stays where it was booked; the un-stamped one still asks the holding.
        assertThat(paid.investments()).isEqualByComparingTo("300");
        assertThat(paid.savings()).isEqualByComparingTo("700");
    }
}
