package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.MonthlyPaymentPayRequest;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Both of the owner's bills were paid once in September and then read "next due in November".
 * Paying moved the stored date a month forward EVERY time — so a second payment in the same month,
 * or a date already set a month ahead when the bill was created, pushed it past the month that
 * was actually next. The next due date is now the due day of the month after the payment's month.
 */
class MonthlyPaymentNextDueTest {

    private MonthlyPaymentRepository monthlyPaymentRepository;
    private FinanceService service;

    @BeforeEach
    void setUp() {
        monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        service = new FinanceService(
                mock(DebtRepository.class),
                mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class),
                mock(BankLoanRepository.class),
                monthlyPaymentRepository,
                mock(DonationRepository.class),
                mock(InvestmentRepository.class),
                mock(CategoryRepository.class),
                mock(TransactionRepository.class),
                mock(CardRepository.class),
                mock(MarkPaidRepository.class),
                mock(CardService.class),
                mock(MonthCloseService.class),
                mock(SettingsService.class));
    }

    private MonthlyPayment bill(int dueDay, LocalDate nextDueDate) {
        MonthlyPayment m = new MonthlyPayment();
        m.setId(1L);
        m.setName("Kvartira Arenda");
        m.setAmount(new BigDecimal("4200000"));
        m.setCurrency(Currency.UZS);
        m.setDueDay(dueDay);
        m.setActive(true);
        m.setNextDueDate(nextDueDate);
        when(monthlyPaymentRepository.findById(1L)).thenReturn(Optional.of(m));
        return m;
    }

    private void pay(String amount, LocalDate on) {
        MonthlyPaymentPayRequest req = new MonthlyPaymentPayRequest();
        req.setAmount(new BigDecimal(amount));
        req.setPaymentDate(on);
        req.setMode(MonthlyPaymentPayRequest.Mode.CASH);
        service.payMonthlyPayment(1L, req);
    }

    @Test
    void paidOnceInSeptemberItIsNextDueInOctober() {
        MonthlyPayment rent = bill(10, null);

        pay("4200000", LocalDate.of(2026, 9, 8));

        assertThat(rent.getNextDueDate()).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void aSecondPaymentInTheSameMonthDoesNotSkipAMonth() {
        MonthlyPayment rent = bill(10, null);

        pay("2000000", LocalDate.of(2026, 9, 8));
        pay("2200000", LocalDate.of(2026, 9, 20));   // the rest — the old code said 10 November

        assertThat(rent.getNextDueDate()).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void aDateAlreadySetAMonthAheadIsNotPushedFurther() {
        MonthlyPayment noon = bill(9, LocalDate.of(2026, 10, 9));   // typed into the old form at creation

        pay("1100000", LocalDate.of(2026, 9, 9));

        assertThat(noon.getNextDueDate()).isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @Test
    void theDueDayIsClampedToAShortMonth() {
        MonthlyPayment internet = bill(31, null);

        pay("200000", LocalDate.of(2026, 1, 31));

        assertThat(internet.getNextDueDate()).isEqualTo(LocalDate.of(2026, 2, 28));
    }
}
