package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
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
 * The Overview renders the tier and the ledger side by side: the cards come from the tier, the
 * "This month / Allocation due" header from the ledger. So the two must never disagree about
 * whether guidance exists at all — the header printing a concrete monthly ask directly above
 * cards that say guidance is unavailable is exactly what the audit caught.
 *
 * <p>Three gates withhold guidance: no stable income, a month before the configured tracking
 * start, and unpaid mandatory subscriptions. The tier honoured all three; the ledger honoured
 * only the first two. This asserts the whole matrix rather than the one gate that regressed,
 * so adding a fourth gate to one service and not the other fails here.
 */
class AllocationGuidanceAgreementTest {

    private Settings settings;
    private MonthlyPaymentRepository monthlyPaymentRepository;
    private TransactionRepository transactionRepository;
    private OverviewService service;

    private static final YearMonth AUG = YearMonth.of(2026, 8);

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        MarkPaidRepository markPaidRepository = mock(MarkPaidRepository.class);
        DonationRepository donationRepository = mock(DonationRepository.class);
        InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        LevelAllocationRuleRepository ruleRepository = mock(LevelAllocationRuleRepository.class);
        LevelConfigRepository levelConfigRepository = mock(LevelConfigRepository.class);
        SettingsService settingsService = mock(SettingsService.class);

        settings = new Settings();
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
        when(markPaidRepository.findByMonth(any())).thenReturn(List.of());
        when(markPaidRepository.findByKindAndRefIdAndMonth(any(), any(), any())).thenReturn(List.of());
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(any(), any()))
                .thenReturn(List.of());
        when(investmentRepository.findById(any())).thenReturn(Optional.empty());
        when(investmentRepository.findByOriginatingTransactionId(any())).thenReturn(Optional.empty());

        service = new OverviewService(
                transactionRepository, monthlyPaymentRepository, bankLoanRepository,
                loanTakenRepository, debtRepository, donationRepository, investmentRepository,
                ruleRepository, levelConfigRepository, markPaidRepository, settingsService);
    }

    /**
     * Asserts the two halves of the page tell the same story. Guidance is available only when no
     * gate is closed; when one is, the ledger must withhold its dues too, not just raise a flag.
     */
    private void assertTierAndLedgerAgree(String state) {
        OverviewTierResponse tier = service.getTier(AUG, Currency.UZS);
        AllocationLedgerResponse ledger = service.getAllocationLedger(AUG, Currency.UZS);

        assertThat(ledger.isMissingStableIncome()).as("%s: missingStableIncome", state)
                .isEqualTo(tier.isMissingStableIncome());
        assertThat(ledger.isBeforeTrackingStart()).as("%s: beforeTrackingStart", state)
                .isEqualTo(tier.isBeforeTrackingStart());
        assertThat(ledger.isSubscriptionsPending()).as("%s: subscriptionsPending", state)
                .isEqualTo(tier.isSubscriptionsPending());

        boolean guidance = !tier.isMissingStableIncome()
                && !tier.isBeforeTrackingStart()
                && !tier.isSubscriptionsPending();

        assertThat(tier.getAllocation().getLines().isEmpty()).as("%s: tier cards", state)
                .isNotEqualTo(guidance);
        assertThat(ledger.getBuckets().isEmpty()).as("%s: ledger cards", state)
                .isNotEqualTo(guidance);
        // The header's own figures: a withheld ledger must carry no ask at all, or the page
        // promises a number above the sentence saying it cannot advise one.
        if (guidance) {
            assertThat(ledger.getDueThisMonth()).as("%s: dueThisMonth", state).isNotNull();
            assertThat(ledger.getTotalDueNow()).as("%s: totalDueNow", state).isNotNull();
        } else {
            assertThat(ledger.getDueThisMonth()).as("%s: dueThisMonth", state).isNull();
            assertThat(ledger.getTotalDueNow()).as("%s: totalDueNow", state).isNull();
            assertThat(ledger.getMonths()).as("%s: ledger months", state).isEmpty();
        }
    }

    @Test
    void withEveryGateOpenBothSidesOfferGuidance() {
        assertTierAndLedgerAgree("all clear");
    }

    @Test
    void withNoStableIncomeBothSidesWithholdGuidance() {
        settings.setMonthlyStableIncome(null);

        assertTierAndLedgerAgree("no stable income");
        assertThat(service.getTier(AUG, Currency.UZS).isMissingStableIncome()).isTrue();
    }

    @Test
    void beforeTheTrackingStartMonthBothSidesWithholdGuidance() {
        settings.setAllocationTrackingStartMonth(AUG.plusMonths(1).atDay(1));

        assertTierAndLedgerAgree("before tracking start");
        assertThat(service.getTier(AUG, Currency.UZS).isBeforeTrackingStart()).isTrue();
    }

    /**
     * Mandatory subscriptions come first: until they are covered the tier withholds its whole
     * allocation. The ledger did not check this at all, so the header quoted a monthly ask over
     * cards saying guidance was unavailable.
     */
    @Test
    void withAnUnpaidMandatorySubscriptionBothSidesWithholdGuidance() {
        MonthlyPayment sub = new MonthlyPayment();
        sub.setId(5L);
        sub.setName("Internet");
        sub.setAmount(new BigDecimal("300000"));
        sub.setCurrency(Currency.UZS);
        sub.setActive(true);
        when(monthlyPaymentRepository.findAll()).thenReturn(List.of(sub));
        when(transactionRepository.sumByMonthlyPaymentIdAndDateRange(any(), any(), any())).thenReturn(null);

        assertTierAndLedgerAgree("subscription unpaid");
        assertThat(service.getTier(AUG, Currency.UZS).isSubscriptionsPending()).isTrue();
    }

    /** Paying the subscription reopens the gate on both sides at once. */
    @Test
    void payingTheSubscriptionReopensGuidanceOnBothSides() {
        MonthlyPayment sub = new MonthlyPayment();
        sub.setId(5L);
        sub.setName("Internet");
        sub.setAmount(new BigDecimal("300000"));
        sub.setCurrency(Currency.UZS);
        sub.setActive(true);
        when(monthlyPaymentRepository.findAll()).thenReturn(List.of(sub));
        when(transactionRepository.sumByMonthlyPaymentIdAndDateRange(5L, AUG.atDay(1), AUG.atEndOfMonth()))
                .thenReturn(new BigDecimal("300000"));

        assertTierAndLedgerAgree("subscription paid");
        assertThat(service.getTier(AUG, Currency.UZS).isSubscriptionsPending()).isFalse();
    }
}
