package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.MarkPaidRepository;
import uz.tracker.trackerproject.repository.MonthlyPaymentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting an investment must take its own mirror transaction with it (otherwise the orphan
 * keeps crediting the allocation bucket for a fund that no longer exists), while still
 * refusing to delete one that has separate contribution transactions attached.
 */
class FinanceServiceInvestmentDeleteTest {

    private InvestmentRepository investmentRepository;
    private TransactionRepository transactionRepository;
    private MonthCloseService monthCloseService;
    private FinanceService service;

    @BeforeEach
    void setUp() {
        investmentRepository = mock(InvestmentRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        monthCloseService = mock(MonthCloseService.class);
        service = new FinanceService(
                mock(DebtRepository.class),
                mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class),
                mock(BankLoanRepository.class),
                mock(MonthlyPaymentRepository.class),
                mock(DonationRepository.class),
                investmentRepository,
                mock(CategoryRepository.class),
                transactionRepository,
                mock(CardRepository.class),
                mock(MarkPaidRepository.class),
                mock(CardService.class),
                monthCloseService,
                mock(SettingsService.class));
    }

    private Transaction tx(long id, String amount) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setType(TransactionType.EXPENSE);
        t.setSubType(TransactionSubType.EMERGENCY_CONTRIBUTION);
        t.setInvestmentId(7L);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency(Currency.UZS);
        t.setTransactionDate(LocalDate.of(2026, 8, 15));
        return t;
    }

    private Investment fund(Long originatingTxId) {
        Investment i = new Investment();
        i.setId(7L);
        i.setName("Emergency fund");
        i.setInvestedAmount(new BigDecimal("500"));
        i.setCurrency(Currency.UZS);
        i.setEmergencyFund(true);
        i.setOriginatingTransactionId(originatingTxId);
        return i;
    }

    @Test
    void deletingAFundAlsoDeletesItsOwnMirrorTransaction() {
        Investment i = fund(20L);
        Transaction mirror = tx(20L, "500");
        when(investmentRepository.findById(7L)).thenReturn(Optional.of(i));
        when(transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(7L))
                .thenReturn(List.of(mirror));
        when(transactionRepository.findById(20L)).thenReturn(Optional.of(mirror));

        service.deleteInvestment(7L);

        // No orphan left crediting the Emergency bucket, and the fund is gone.
        verify(transactionRepository).delete(mirror);
        verify(investmentRepository).delete(i);
        verify(monthCloseService).assertMonthOpen(LocalDate.of(2026, 8, 15));
    }

    @Test
    void aFundWithContributionsStillRefusesDeletion() {
        Investment i = fund(20L);
        when(investmentRepository.findById(7L)).thenReturn(Optional.of(i));
        when(transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(7L))
                .thenReturn(List.of(tx(20L, "500"), tx(21L, "300")));

        assertThatThrownBy(() -> service.deleteInvestment(7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 transaction(s)");

        verify(investmentRepository, never()).delete(any());
        verify(transactionRepository, never()).delete(any(Transaction.class));
    }

    @Test
    void anOpeningBalanceInvestmentWithNoMirrorDeletesCleanly() {
        Investment i = fund(null); // opening balance: never mirrored a transaction
        when(investmentRepository.findById(7L)).thenReturn(Optional.of(i));
        when(transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(7L))
                .thenReturn(List.of());

        service.deleteInvestment(7L);

        verify(investmentRepository).delete(i);
        verify(transactionRepository, never()).delete(any(Transaction.class));
        assertThat(i.getOriginatingTransactionId()).isNull();
    }

    @Test
    void closedMonthBlocksDeletingTheFundAndItsMirror() {
        Investment i = fund(20L);
        Transaction mirror = tx(20L, "500");
        when(investmentRepository.findById(7L)).thenReturn(Optional.of(i));
        when(transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(7L))
                .thenReturn(List.of(mirror));
        when(transactionRepository.findById(20L)).thenReturn(Optional.of(mirror));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("month is closed"))
                .when(monthCloseService).assertMonthOpen(any());

        assertThatThrownBy(() -> service.deleteInvestment(7L))
                .isInstanceOf(IllegalArgumentException.class);

        verify(investmentRepository, never()).delete(any());
    }
}
