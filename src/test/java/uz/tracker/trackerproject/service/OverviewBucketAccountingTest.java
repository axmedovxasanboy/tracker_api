package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.repository.*;

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
 * The Investments and Savings buckets count INVESTMENT transactions BY TRANSACTION DATE
 * (like Emergency and Stocks), not Investment entities by purchaseDate. The old behaviour
 * credited a later top-up to the month the holding was first bought, so money spent this
 * month was invisible and a past month silently grew.
 */
class OverviewBucketAccountingTest {

    private TransactionRepository transactionRepository;
    private InvestmentRepository investmentRepository;
    private DonationRepository donationRepository;
    private OverviewService service;

    private static final YearMonth AUG = YearMonth.of(2026, 8);

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        investmentRepository = mock(InvestmentRepository.class);
        donationRepository = mock(DonationRepository.class);
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

    private Transaction investmentTx(long id, Long investmentId, String amount, LocalDate date) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setSubType(TransactionSubType.INVESTMENT);
        t.setInvestmentId(investmentId);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency(Currency.UZS);
        t.setTransactionDate(date);
        return t;
    }

    private void givenInvestmentTxsInAugust(Transaction... txs) {
        when(transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                eq(TransactionSubType.INVESTMENT), eq(AUG.atDay(1)), eq(AUG.atEndOfMonth())))
                .thenReturn(List.of(txs));
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

    @Test
    void topUpOfAnExistingInvestmentCountsInTheMonthItWasSpent() {
        // Bought in June, topped up in August: the top-up belongs to AUGUST.
        givenInvestmentTxsInAugust(investmentTx(10L, 7L, "300", LocalDate.of(2026, 8, 15)));
        when(investmentRepository.findById(7L)).thenReturn(Optional.of(investment(7L, false)));

        var paid = service.computePaidThisMonth(AUG, Currency.UZS);

        assertThat(paid.investments()).isEqualByComparingTo("300");
        assertThat(paid.savings()).isEqualByComparingTo("0");
    }

    @Test
    void aTopUpAimedAtASavingsGoalLandsInSavingsNotInvestments() {
        givenInvestmentTxsInAugust(investmentTx(11L, 8L, "500", LocalDate.of(2026, 8, 3)));
        when(investmentRepository.findById(8L)).thenReturn(Optional.of(investment(8L, true)));

        var paid = service.computePaidThisMonth(AUG, Currency.UZS);

        assertThat(paid.savings()).isEqualByComparingTo("500");
        assertThat(paid.investments()).isEqualByComparingTo("0");
    }

    @Test
    void aNewInvestmentIsRoutedViaItsOriginatingTransaction() {
        // Created from the Transactions page: the tx carries no investmentId; the record
        // points back at it instead.
        Transaction tx = investmentTx(12L, null, "700", LocalDate.of(2026, 8, 9));
        givenInvestmentTxsInAugust(tx);
        when(investmentRepository.findByOriginatingTransactionId(12L))
                .thenReturn(Optional.of(investment(9L, true)));

        var paid = service.computePaidThisMonth(AUG, Currency.UZS);

        assertThat(paid.savings()).isEqualByComparingTo("700");
    }

    @Test
    void recordOnlyContributionsAreExcludedBecauseTheyBookNoTransaction() {
        // openingBalance / noWallet move no money and create no transaction, so there is
        // nothing to find — the bucket must stay empty without any flag check.
        var paid = service.computePaidThisMonth(AUG, Currency.UZS);

        assertThat(paid.investments()).isEqualByComparingTo("0");
        assertThat(paid.savings()).isEqualByComparingTo("0");
    }
}
