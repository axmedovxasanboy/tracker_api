package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.tracker.trackerproject.dto.request.BalanceTransferRequest;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.entity.CashBalance;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.CardType;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cash sits on either side of a balance transfer as a transaction with no card. These pin
 * the two invariants that make that work: the cardless row must carry cashAmount (the cash
 * balance query reads it), and cash must not be able to overdraw into a card — that would
 * create money rather than record an overspend. Pure Mockito — no Spring context.
 */
class TransactionServiceCashTransferTest {

    private TransactionRepository transactionRepository;
    private CardRepository cardRepository;
    private CashBalanceRepository cashBalanceRepository;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        cardRepository = mock(CardRepository.class);
        cashBalanceRepository = mock(CashBalanceRepository.class);
        service = new TransactionService(
                transactionRepository,
                mock(CategoryRepository.class),
                cardRepository,
                cashBalanceRepository,
                mock(FinanceService.class),
                mock(MonthCloseService.class),
                mock(SettingsService.class),
                mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class),
                mock(DonationRepository.class),
                mock(InvestmentRepository.class));

        // Persisted rows get ids so the transfer-pair linking has something to work with.
        AtomicLong seq = new AtomicLong(100);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(seq.incrementAndGet());
            return t;
        });
    }

    private Card card(long id, String initialBalance) {
        Card c = new Card();
        c.setId(id);
        c.setName("Humo card");
        c.setBankName("Humo card");
        c.setType(CardType.HUMO);
        c.setLastFourDigits("4521");
        c.setCurrency(Currency.UZS);
        c.setInitialBalance(new BigDecimal(initialBalance));
        when(cardRepository.findById(id)).thenReturn(Optional.of(c));
        when(cardRepository.sumTransactionsByCardId(id)).thenReturn(BigDecimal.ZERO);
        return c;
    }

    private void cashPot(String initialBalance, String delta) {
        CashBalance pot = new CashBalance();
        pot.setCurrency(Currency.UZS);
        pot.setInitialBalance(new BigDecimal(initialBalance));
        when(cashBalanceRepository.findByCurrency(Currency.UZS)).thenReturn(Optional.of(pot));
        when(cashBalanceRepository.sumCashlessTransactions(Currency.UZS)).thenReturn(new BigDecimal(delta));
    }

    private BalanceTransferRequest req(Long fromCardId, Long toCardId, String amount) {
        BalanceTransferRequest r = new BalanceTransferRequest();
        r.setFromCardId(fromCardId);
        r.setToCardId(toCardId);
        r.setAmount(new BigDecimal(amount));
        r.setTransactionDate(LocalDate.of(2026, 9, 4));
        return r;
    }

    private List<Transaction> savedRows() {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        // Each row is saved twice: once to persist, once to stamp the transfer pair id.
        verify(transactionRepository, times(4)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void cashToCardLeavesTheExpenseCardlessAndCarryingCashAmount() {
        card(7L, "0");
        cashPot("500000", "0");

        service.transferBalance(req(null, 7L, "200000"));

        List<Transaction> rows = savedRows();
        Transaction expense = rows.stream().filter(t -> t.getType() == TransactionType.EXPENSE).findFirst().orElseThrow();
        Transaction income = rows.stream().filter(t -> t.getType() == TransactionType.INCOME).findFirst().orElseThrow();

        // The cash side must be cardless AND carry cashAmount, or the cash balance
        // query cannot see the money leaving the pot.
        assertThat(expense.getCard()).isNull();
        assertThat(expense.getCashAmount()).isEqualByComparingTo("200000");
        assertThat(expense.getSubType()).isEqualTo(TransactionSubType.TRANSFER_OUT);
        assertThat(expense.getNote()).contains("Humo card");

        assertThat(income.getCard()).isNotNull();
        assertThat(income.getCashAmount()).isNull();
        assertThat(income.getSubType()).isEqualTo(TransactionSubType.TRANSFER_IN);
        assertThat(income.getNote()).contains("Cash");
        assertThat(income.getCurrency()).isEqualTo(Currency.UZS);
    }

    @Test
    void cardToCashLeavesTheIncomeCardlessAndCarryingCashAmount() {
        card(7L, "800000");
        cashPot("0", "0");

        service.transferBalance(req(7L, null, "300000"));

        List<Transaction> rows = savedRows();
        Transaction expense = rows.stream().filter(t -> t.getType() == TransactionType.EXPENSE).findFirst().orElseThrow();
        Transaction income = rows.stream().filter(t -> t.getType() == TransactionType.INCOME).findFirst().orElseThrow();

        assertThat(expense.getCard()).isNotNull();
        assertThat(expense.getCashAmount()).isNull();
        assertThat(income.getCard()).isNull();
        assertThat(income.getCashAmount()).isEqualByComparingTo("300000");
        assertThat(expense.getNote()).contains("Cash");
    }

    @Test
    void cashCannotOverdrawIntoACard() {
        card(7L, "0");
        cashPot("100000", "-40000"); // 60 000 actually in the pot

        assertThatThrownBy(() -> service.transferBalance(req(null, 7L, "80000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient cash balance");

        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void aCashOnlyTransferIsRejected() {
        assertThatThrownBy(() -> service.transferBalance(req(null, null, "50000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one side");

        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void cardToCardStillWorksUnchanged() {
        card(7L, "900000");
        Card other = new Card();
        other.setId(8L);
        other.setName("Uzcard");
        other.setBankName("Uzcard");
        other.setType(CardType.UZCARD);
        other.setLastFourDigits("9988");
        other.setCurrency(Currency.UZS);
        other.setInitialBalance(BigDecimal.ZERO);
        when(cardRepository.findById(8L)).thenReturn(Optional.of(other));

        service.transferBalance(req(7L, 8L, "100000"));

        List<Transaction> rows = savedRows();
        assertThat(rows).allMatch(t -> t.getCard() != null);
        assertThat(rows).allMatch(t -> t.getCashAmount() == null);
        // Never consults the cash pot for a pure card↔card move.
        verify(cashBalanceRepository, never()).sumCashlessTransactions(any());
    }
}
