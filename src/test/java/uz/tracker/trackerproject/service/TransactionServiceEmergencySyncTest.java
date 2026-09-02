package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.TransactionRequest;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CashBalanceRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the Transaction ↔ Investment sync layer for the EMERGENCY_CONTRIBUTION sub-type
 * (emergency funds are investments since the Batch-23 pivot): deleting or re-filing a
 * contribution tx must back its amount out of the fund, and deleting the tx that
 * originated a fund must delete the fund itself. Pure Mockito — no Spring context.
 */
class TransactionServiceEmergencySyncTest {

    private TransactionRepository transactionRepository;
    private InvestmentRepository investmentRepository;
    private FinanceService financeService;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        investmentRepository = mock(InvestmentRepository.class);
        financeService = mock(FinanceService.class);
        service = new TransactionService(
                transactionRepository,
                mock(CategoryRepository.class),
                mock(CardRepository.class),
                mock(CashBalanceRepository.class),
                financeService,
                mock(MonthCloseService.class),
                mock(SettingsService.class),
                mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class),
                mock(DonationRepository.class),
                investmentRepository);
    }

    private Transaction emergencyTx(long id, long investmentId, String amount) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setType(TransactionType.EXPENSE);
        tx.setSubType(TransactionSubType.EMERGENCY_CONTRIBUTION);
        tx.setInvestmentId(investmentId);
        tx.setAmount(new BigDecimal(amount));
        tx.setCashAmount(new BigDecimal(amount));
        tx.setCurrency(Currency.UZS);
        tx.setTransactionDate(LocalDate.of(2026, 8, 15));
        tx.setDescription("Emergency top-up");
        return tx;
    }

    private Investment fund() {
        Investment i = new Investment();
        i.setId(7L);
        i.setName("Emergency fund");
        i.setInvestedAmount(new BigDecimal("500"));
        i.setCurrency(Currency.UZS);
        i.setEmergencyFund(true);
        return i;
    }

    private TransactionRequest cashRequest(TransactionSubType subType, long investmentId, String amount) {
        TransactionRequest req = new TransactionRequest();
        req.setType(TransactionType.EXPENSE);
        req.setSubType(subType);
        req.setInvestmentId(investmentId);
        req.setAmount(new BigDecimal(amount));
        req.setCurrency(Currency.UZS);
        req.setTransactionDate(LocalDate.of(2026, 8, 15));
        req.setDescription("Emergency top-up");
        return req;
    }

    @Test
    void deletingEmergencyTopUpBacksFundsOutOfTheInvestment() {
        Transaction tx = emergencyTx(10L, 7L, "300");
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(tx));
        when(investmentRepository.findByOriginatingTransactionId(10L)).thenReturn(Optional.empty());

        service.delete(10L);

        verify(financeService).removeFundsFromInvestment(7L, new BigDecimal("300"));
        verify(transactionRepository).delete(tx);
    }

    @Test
    void deletingTheOriginatingTxOfAnEmergencyFundDeletesTheFund() {
        Transaction tx = emergencyTx(11L, 7L, "500");
        Investment fund = fund();
        when(transactionRepository.findById(11L)).thenReturn(Optional.of(tx));
        when(investmentRepository.findByOriginatingTransactionId(11L)).thenReturn(Optional.of(fund));
        // Only the fund's own mirror row is linked — nothing else would be stranded.
        when(transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(7L))
                .thenReturn(List.of(tx));

        service.delete(11L);

        verify(investmentRepository).delete(fund);
        verify(financeService, never()).removeFundsFromInvestment(anyLong(), any());
    }

    @Test
    void deletingTheOriginatingTxIsRefusedWhileContributionsWouldBeStranded() {
        Transaction originating = emergencyTx(11L, 7L, "500");
        Transaction contribution = emergencyTx(12L, 7L, "300");
        when(transactionRepository.findById(11L)).thenReturn(Optional.of(originating));
        when(investmentRepository.findByOriginatingTransactionId(11L)).thenReturn(Optional.of(fund()));
        when(transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(7L))
                .thenReturn(List.of(originating, contribution));

        assertThatThrownBy(() -> service.delete(11L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Emergency fund")
                .hasMessageContaining("1 contribution transaction(s)");

        // Nothing is removed: no stranded contribution, no half-deleted fund.
        verify(investmentRepository, never()).delete(any(Investment.class));
        verify(transactionRepository, never()).delete(any(Transaction.class));
    }

    @Test
    void refilingEmergencyContributionToInvestmentDoesNotDoubleAdd() {
        Transaction tx = emergencyTx(12L, 7L, "300");
        when(transactionRepository.findById(12L)).thenReturn(Optional.of(tx));
        when(investmentRepository.findByOriginatingTransactionId(12L)).thenReturn(Optional.empty());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(12L, cashRequest(TransactionSubType.INVESTMENT, 7L, "300"));

        // The reversal and the re-create cancel out — the invested total must end unchanged.
        verify(financeService).removeFundsFromInvestment(7L, new BigDecimal("300"));
        verify(financeService).addFundsToInvestment(7L, new BigDecimal("300"));
    }

    @Test
    void editingAContributionAmountAppliesOnlyTheDiff() {
        Transaction tx = emergencyTx(13L, 7L, "300");
        when(transactionRepository.findById(13L)).thenReturn(Optional.of(tx));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(13L, cashRequest(TransactionSubType.EMERGENCY_CONTRIBUTION, 7L, "400"));

        verify(financeService).addFundsToInvestment(7L, new BigDecimal("100"));
        verify(financeService, never()).removeFundsFromInvestment(anyLong(), any());
    }
}
