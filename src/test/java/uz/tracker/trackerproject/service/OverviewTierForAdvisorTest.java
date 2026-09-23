package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Plan withholds the whole allocation until every subscription is paid; the advisor asks for
 * the same tier with that one gate lifted, so it can say "after the rent, set aside X". Nothing
 * else may differ between the two — same base, same percentages, and the pending subscriptions
 * still reported so the advisor puts them first.
 */
class OverviewTierForAdvisorTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private OverviewService service;

    @BeforeEach
    void setUp() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
        LevelAllocationRuleRepository ruleRepository = mock(LevelAllocationRuleRepository.class);
        MarkPaidRepository markPaidRepository = mock(MarkPaidRepository.class);
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        DonationRepository donationRepository = mock(DonationRepository.class);
        LevelConfigRepository levelConfigRepository = mock(LevelConfigRepository.class);
        SettingsService settingsService = mock(SettingsService.class);

        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("10000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        when(settingsService.getOrCreate()).thenReturn(settings);

        MonthlyPayment rent = new MonthlyPayment();
        rent.setId(1L);
        rent.setName("Rent");
        rent.setAmount(new BigDecimal("4200000"));
        rent.setCurrency(Currency.UZS);
        rent.setActive(true);
        when(monthlyPaymentRepository.findAll()).thenReturn(List.of(rent));

        when(bankLoanRepository.findAll()).thenReturn(List.of());
        when(debtRepository.findAll()).thenReturn(List.of());
        when(loanTakenRepository.findAll()).thenReturn(List.of());
        when(investmentRepository.findAll()).thenReturn(List.of());
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(any(), any())).thenReturn(List.of());
        when(markPaidRepository.findByKindAndRefIdAndMonth(any(), any(), any())).thenReturn(List.of());
        when(markPaidRepository.findByMonth(any())).thenReturn(List.of());
        when(levelConfigRepository.findByLevel(anyInt())).thenReturn(Optional.empty());
        when(ruleRepository.findBySubLevel(any())).thenReturn(Optional.empty());
        when(transactionRepository.sumBySubTypeCurrencyDateRange(any(), any(), any(), any())).thenReturn(null);
        when(transactionRepository.sumBonusIncomeByCurrencyDateRange(any(), any(), any())).thenReturn(null);
        when(transactionRepository.sumByMonthlyPaymentIdAndDateRange(any(), any(), any())).thenReturn(null);
        when(transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(any(), any(), any()))
                .thenReturn(List.of());

        service = new OverviewService(
                transactionRepository, monthlyPaymentRepository, bankLoanRepository,
                loanTakenRepository, debtRepository, donationRepository, investmentRepository,
                ruleRepository, levelConfigRepository, markPaidRepository, settingsService, mock(CategoryRepository.class));
    }

    @Test
    void thePlanStillWithholdsTheAllocationWhileTheRentIsUnpaid() {
        OverviewTierResponse plan = service.getTier(SEP, Currency.UZS);

        assertThat(plan.isSubscriptionsPending()).isTrue();
        assertThat(plan.getAllocation().getLines()).isEmpty();
    }

    @Test
    void theAdvisorGetsTheSameAllocationWithTheRentStillListedAsPending() {
        OverviewTierResponse advisor = service.getTierIgnoringSubscriptions(SEP, Currency.UZS);

        assertThat(advisor.isSubscriptionsPending()).isTrue();
        assertThat(advisor.getPendingSubscriptions()).singleElement()
                .satisfies(p -> assertThat(p.getName()).isEqualTo("Rent"));
        // Level 1.1 (10M − 4.2M = 5.8M left): 10 / 5 / 15 % of what the owner earns — the 10M stable
        // income, while no salary is recorded yet.
        assertThat(advisor.getAllocation().getLines())
                .extracting(AllocationLine::getBucket, l -> l.getMinAmount().stripTrailingZeros().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("DONATION", "1000000"),
                        org.assertj.core.groups.Tuple.tuple("EMERGENCY", "500000"),
                        org.assertj.core.groups.Tuple.tuple("INVESTMENTS", "1500000"));
        assertThat(advisor.getAllocationBase()).isEqualByComparingTo(service.getTier(SEP, Currency.UZS).getAllocationBase());
    }
}
