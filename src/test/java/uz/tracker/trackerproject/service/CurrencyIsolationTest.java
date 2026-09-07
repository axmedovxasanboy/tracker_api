package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * USD/EUR exist only as standalone cash pots — nothing converts them to som. Every figure
 * the app reports (income, the allocation buckets, net worth) is denominated in UZS, so a
 * foreign amount must never be summed into one.
 *
 * <p>The trap this guards: while the enum held UZS alone, `for (Currency c : values())` was a
 * safe way to write "sum the money". Re-adding members turned each such loop into a
 * cross-currency sum at once. These pin the invariant that aggregation iterates
 * {@link Currency#reporting()} instead.
 */
class CurrencyIsolationTest {

    private TransactionRepository transactionRepository;
    private OverviewService service;

    private static final YearMonth AUG = YearMonth.of(2026, 8);

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        DonationRepository donationRepository = mock(DonationRepository.class);
        SettingsService settingsService = mock(SettingsService.class);
        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("5000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        when(settingsService.getOrCreate()).thenReturn(settings);
        service = new OverviewService(
                transactionRepository,
                mock(MonthlyPaymentRepository.class),
                mock(BankLoanRepository.class),
                mock(LoanTakenRepository.class),
                mock(DebtRepository.class),
                donationRepository,
                mock(InvestmentRepository.class),
                mock(LevelAllocationRuleRepository.class),
                mock(LevelConfigRepository.class),
                mock(MarkPaidRepository.class),
                settingsService);
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(any(), any()))
                .thenReturn(List.of());
        when(transactionRepository.sumBySubTypeCurrencyDateRange(any(), any(), any(), any()))
                .thenReturn(null);
        when(transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void onlyUzsRollsUpIntoTheBooks() {
        assertThat(Currency.reporting()).containsExactly(Currency.UZS);
    }

    @Test
    void foreignIncomeIsNeverAddedToTheMonthlyIncomeFigure() {
        // A USD cash pot's top-up is a real USD transaction. If income summing walked the
        // whole enum it would be added to a som figure at an implied 1:1 rate.
        when(transactionRepository.sumByTypeCurrencyDateRange(any(), eq(Currency.UZS), any(), any()))
                .thenReturn(new BigDecimal("5000000"));
        when(transactionRepository.sumByTypeCurrencyDateRange(any(), eq(Currency.USD), any(), any()))
                .thenReturn(new BigDecimal("900"));

        service.getIncome(AUG, Currency.UZS);

        verify(transactionRepository, never())
                .sumByTypeCurrencyDateRange(any(), eq(Currency.USD), any(), any());
        verify(transactionRepository, never())
                .sumByTypeCurrencyDateRange(any(), eq(Currency.EUR), any(), any());
    }

    @Test
    void foreignSpendIsNeverCreditedToAnAllocationBucket() {
        when(transactionRepository.sumBySubTypeCurrencyDateRange(
                eq(TransactionSubType.STOCK_PURCHASE), eq(Currency.USD), any(), any()))
                .thenReturn(new BigDecimal("400"));

        service.computePaidThisMonth(AUG, Currency.UZS);

        for (Currency foreign : List.of(Currency.USD, Currency.EUR)) {
            verify(transactionRepository, never())
                    .sumBySubTypeCurrencyDateRange(any(), eq(foreign), any(), any());
        }
    }
}
