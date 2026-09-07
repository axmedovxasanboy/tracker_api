package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.TransactionRequest;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lending more to someone who already owes you must raise THEIR loan, not open a second
 * record for the same person — and editing or deleting that top-up must back the amount out
 * again, or the borrower is left owing money that was never lent.
 */
class TransactionServiceLoanTopUpTest {

    private TransactionRepository transactionRepository;
    private LoanGivenRepository loanGivenRepository;
    private FinanceService financeService;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        loanGivenRepository = mock(LoanGivenRepository.class);
        financeService = mock(FinanceService.class);
        service = new TransactionService(
                transactionRepository,
                mock(CategoryRepository.class),
                mock(CardRepository.class),
                mock(CashBalanceRepository.class),
                financeService,
                mock(MonthCloseService.class),
                mock(SettingsService.class),
                loanGivenRepository,
                mock(LoanTakenRepository.class),
                mock(DonationRepository.class),
                mock(InvestmentRepository.class));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(55L);
            return t;
        });
    }

    private TransactionRequest lend(Long loanGivenId, String amount) {
        TransactionRequest r = new TransactionRequest();
        r.setType(TransactionType.EXPENSE);
        r.setSubType(TransactionSubType.LOAN_GIVEN);
        r.setLoanGivenId(loanGivenId);
        r.setCounterpartyName("Aziz");
        r.setDescription("Lent to Aziz");
        r.setAmount(new BigDecimal(amount));
        r.setCurrency(Currency.UZS);
        r.setTransactionDate(LocalDate.of(2026, 9, 4));
        return r;
    }

    private Transaction topUpTx(long id, Long loanGivenId, String amount) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setType(TransactionType.EXPENSE);
        t.setSubType(TransactionSubType.LOAN_GIVEN);
        t.setLoanGivenId(loanGivenId);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency(Currency.UZS);
        t.setTransactionDate(LocalDate.of(2026, 9, 4));
        t.setDescription("Lent to Aziz");
        return t;
    }

    @Test
    void lendingAgainRaisesTheExistingLoanInsteadOfCreatingASecond() {
        service.create(lend(9L, "300000"));

        verify(financeService).addToLoanGiven(9L, new BigDecimal("300000"));
        // No new borrower row for a person who already has one.
        verify(financeService, never()).createLoanGivenFromTransaction(any(), anyLong());
    }

    @Test
    void aBrandNewBorrowerStillGetsItsOwnRecord() {
        service.create(lend(null, "500000"));

        verify(financeService).createLoanGivenFromTransaction(any(), eq(55L));
        verify(financeService, never()).addToLoanGiven(anyLong(), any());
    }

    @Test
    void deletingATopUpBacksTheAmountOutOfTheBorrowersTotal() {
        Transaction tx = topUpTx(20L, 9L, "300000");
        when(transactionRepository.findById(20L)).thenReturn(Optional.of(tx));
        // Not the tx that opened the loan — only a later top-up.
        when(loanGivenRepository.findByOriginatingTransactionId(20L)).thenReturn(Optional.empty());

        service.delete(20L);

        verify(financeService).removeFromLoanGiven(9L, new BigDecimal("300000"));
        verify(loanGivenRepository, never()).delete(any(LoanGiven.class));
        verify(transactionRepository).delete(tx);
    }

    @Test
    void deletingTheTxThatOpenedTheLoanStillDeletesTheWholeRecord() {
        Transaction tx = topUpTx(21L, null, "500000");
        LoanGiven loan = new LoanGiven();
        loan.setId(9L);
        loan.setDebtorName("Aziz");
        when(transactionRepository.findById(21L)).thenReturn(Optional.of(tx));
        when(loanGivenRepository.findByOriginatingTransactionId(21L)).thenReturn(Optional.of(loan));

        service.delete(21L);

        verify(loanGivenRepository).delete(loan);
        verify(financeService, never()).removeFromLoanGiven(anyLong(), any());
    }

    @Test
    void editingATopUpAppliesOnlyTheDifference() {
        Transaction tx = topUpTx(22L, 9L, "300000");
        when(transactionRepository.findById(22L)).thenReturn(Optional.of(tx));

        service.update(22L, lend(9L, "450000"));

        verify(financeService).addToLoanGiven(9L, new BigDecimal("150000"));
        verify(financeService, never()).removeFromLoanGiven(anyLong(), any());
    }
}
