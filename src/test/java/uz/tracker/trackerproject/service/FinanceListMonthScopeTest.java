package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.entity.Donation;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Donations and Investments tabs are CRUD screens over all-time lists, so their "N rows ·
 * total" headline could never match the month's bucket figure sitting next to it. Both lists can
 * now be scoped to a month; a blank month keeps the all-time behaviour every existing caller
 * depends on, which is what makes the change safe to adopt one page at a time.
 */
class FinanceListMonthScopeTest {

    private DonationRepository donationRepository;
    private InvestmentRepository investmentRepository;
    private FinanceService service;

    private static final YearMonth AUG = YearMonth.of(2026, 8);

    @BeforeEach
    void setUp() {
        donationRepository = mock(DonationRepository.class);
        investmentRepository = mock(InvestmentRepository.class);

        service = new FinanceService(
                mock(DebtRepository.class),
                mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class),
                mock(BankLoanRepository.class),
                mock(MonthlyPaymentRepository.class),
                donationRepository,
                investmentRepository,
                mock(CategoryRepository.class),
                mock(TransactionRepository.class),
                mock(CardRepository.class),
                mock(MarkPaidRepository.class),
                mock(CardService.class),
                mock(MonthCloseService.class),
                mock(SettingsService.class));
    }

    private Donation donation(long id, YearMonth month) {
        Donation d = new Donation();
        d.setId(id);
        d.setRecipientName("Mosque");
        d.setAmount(new BigDecimal("50000"));
        d.setCurrency(Currency.UZS);
        d.setDonationDate(month.atDay(15));
        return d;
    }

    private Investment investment(long id, YearMonth month) {
        Investment i = new Investment();
        i.setId(id);
        i.setName("Bonds");
        i.setInvestedAmount(new BigDecimal("200000"));
        i.setCurrency(Currency.UZS);
        i.setPurchaseDate(month.atDay(3));
        return i;
    }

    @Test
    void donationsAreScopedToTheMonthAskedForAndAllTimeWhenNoneIs() {
        when(donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(
                AUG.atDay(1), AUG.atEndOfMonth())).thenReturn(List.of(donation(1L, AUG)));
        when(donationRepository.findAllByOrderByDonationDateDesc())
                .thenReturn(List.of(donation(1L, AUG), donation(2L, AUG.minusMonths(4))));

        assertThat(service.getAllDonations(AUG.toString())).hasSize(1);
        verify(donationRepository, never()).findAllByOrderByDonationDateDesc();

        assertThat(service.getAllDonations(null)).hasSize(2);
        assertThat(service.getAllDonations("")).hasSize(2);
    }

    /**
     * Investments are long-lived holdings, so the all-time portfolio stays the default — a month
     * only answers "what did I put aside in August", never "what do I own".
     */
    @Test
    void investmentsAreScopedToTheMonthAskedForAndAllTimeWhenNoneIs() {
        when(investmentRepository.findByPurchaseDateBetweenOrderByPurchaseDateDesc(
                AUG.atDay(1), AUG.atEndOfMonth())).thenReturn(List.of(investment(1L, AUG)));
        when(investmentRepository.findAllByOrderByPurchaseDateDesc())
                .thenReturn(List.of(investment(1L, AUG), investment(2L, AUG.minusMonths(9))));

        assertThat(service.getAllInvestments(AUG.toString())).hasSize(1);
        verify(investmentRepository, never()).findAllByOrderByPurchaseDateDesc();

        assertThat(service.getAllInvestments(null)).hasSize(2);
    }

    /** A malformed month is rejected rather than silently answered with the whole history. */
    @Test
    void aMalformedMonthIsRejected() {
        assertThatThrownBy(() -> service.getAllDonations("August"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM");
        verify(donationRepository, never()).findAllByOrderByDonationDateDesc();
    }
}
