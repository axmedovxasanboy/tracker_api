package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.LoanTakenRequest;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The repayment plan has to be changeable after the fact — the first version of this silently
 * dropped edits because the full-update path never copied the field across. Both routes are
 * pinned here: the loan form, and the plan-only endpoint that must not disturb anything else.
 */
class FinanceServiceLoanPlanTest {

    private LoanTakenRepository loanTakenRepository;
    private FinanceService service;

    @BeforeEach
    void setUp() {
        loanTakenRepository = mock(LoanTakenRepository.class);
        service = new FinanceService(
                mock(DebtRepository.class),
                mock(LoanGivenRepository.class),
                loanTakenRepository,
                mock(BankLoanRepository.class),
                mock(MonthlyPaymentRepository.class),
                mock(DonationRepository.class),
                mock(InvestmentRepository.class),
                mock(CategoryRepository.class),
                mock(TransactionRepository.class),
                mock(CardRepository.class),
                mock(MarkPaidRepository.class),
                mock(CardService.class),
                mock(MonthCloseService.class),
                mock(SettingsService.class));
        when(loanTakenRepository.save(any(LoanTaken.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private LoanTaken existing(String planned) {
        LoanTaken l = new LoanTaken();
        l.setId(4L);
        l.setLenderName("Bobur");
        l.setTotalAmount(new BigDecimal("10000000"));
        l.setPaidAmount(new BigDecimal("1000000"));
        l.setCurrency(Currency.UZS);
        l.setBorrowedDate(LocalDate.of(2026, 1, 10));
        l.setStatus(RecordStatus.PENDING);
        if (planned != null) l.setPlannedMonthlyPayment(new BigDecimal(planned));
        when(loanTakenRepository.findById(4L)).thenReturn(Optional.of(l));
        return l;
    }

    private LoanTakenRequest req(String planned) {
        LoanTakenRequest r = new LoanTakenRequest();
        r.setLenderName("Bobur");
        r.setTotalAmount(new BigDecimal("10000000"));
        r.setPaidAmount(new BigDecimal("1000000"));
        r.setCurrency(Currency.UZS);
        r.setBorrowedDate(LocalDate.of(2026, 1, 10));
        if (planned != null) r.setPlannedMonthlyPayment(new BigDecimal(planned));
        return r;
    }

    @Test
    void editingTheLoanPersistsAChangedPlan() {
        LoanTaken l = existing("200000");

        service.updateLoanTaken(4L, req("350000"));

        assertThat(l.getPlannedMonthlyPayment()).isEqualByComparingTo("350000");
    }

    @Test
    void theStandalonePlanEndpointChangesTheAmount() {
        LoanTaken l = existing("200000");

        service.setLoanTakenPlan(4L, new BigDecimal("500000"));

        assertThat(l.getPlannedMonthlyPayment()).isEqualByComparingTo("500000");
        // Nothing else about the loan moves.
        assertThat(l.getTotalAmount()).isEqualByComparingTo("10000000");
        assertThat(l.getPaidAmount()).isEqualByComparingTo("1000000");
        assertThat(l.getLenderName()).isEqualTo("Bobur");
    }

    @Test
    void clearingThePlanRevertsToTheDefaultRule() {
        LoanTaken l = existing("200000");

        service.setLoanTakenPlan(4L, null);

        assertThat(l.getPlannedMonthlyPayment()).isNull();
        assertThat(OverviewService.plannedOrDefaultCharge(l)).isEqualByComparingTo("3400000");
    }

    @Test
    void aZeroAmountAlsoClearsRatherThanZeroingTheDebt() {
        LoanTaken l = existing("200000");

        service.setLoanTakenPlan(4L, BigDecimal.ZERO);

        assertThat(l.getPlannedMonthlyPayment()).isNull();
    }
}
