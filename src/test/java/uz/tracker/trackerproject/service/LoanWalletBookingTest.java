package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.LoanGivenRequest;
import uz.tracker.trackerproject.dto.request.LoanTakenRequest;
import uz.tracker.trackerproject.dto.response.LoanGivenResponse;
import uz.tracker.trackerproject.dto.response.LoanTakenResponse;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Counterparty;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.CounterpartyKind;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RepaymentType;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Borrowed or lent money recorded with {@code moveMoney}: the wallet moves in the same step. The
 * record then originates from a LOAN_RECEIVED / LOAN_GIVEN transaction exactly as if that had been
 * recorded first — so deleting or editing either side moves the other. The bot never sends the
 * flag, and gets the record alone, as before.
 */
class LoanWalletBookingTest {

    private static final LocalDate SEP_14 = LocalDate.of(2026, 9, 14);

    private final List<Transaction> transactions = new ArrayList<>();
    private TransactionRepository transactionRepository;
    private LoanTakenRepository loanTakenRepository;
    private LoanGivenRepository loanGivenRepository;
    private CardService cardService;
    private SettingsService settingsService;
    private MonthCloseService monthCloseService;
    private FinanceService service;
    private Card uzcard;
    private Category borrowedCategory;
    private long nextId = 500;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        loanTakenRepository = mock(LoanTakenRepository.class);
        loanGivenRepository = mock(LoanGivenRepository.class);
        CardRepository cardRepository = mock(CardRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        CounterpartyService counterpartyService = mock(CounterpartyService.class);
        cardService = mock(CardService.class);
        settingsService = mock(SettingsService.class);
        monthCloseService = mock(MonthCloseService.class);

        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(nextId++);
                transactions.add(t);
            }
            return t;
        });
        when(transactionRepository.findById(any())).thenAnswer(inv -> transactions.stream()
                .filter(t -> t.getId().equals(inv.getArgument(0))).findFirst());
        when(loanTakenRepository.save(any(LoanTaken.class))).thenAnswer(inv -> {
            LoanTaken l = inv.getArgument(0);
            if (l.getId() == null) l.setId(nextId++);
            return l;
        });
        when(loanGivenRepository.save(any(LoanGiven.class))).thenAnswer(inv -> {
            LoanGiven l = inv.getArgument(0);
            if (l.getId() == null) l.setId(nextId++);
            return l;
        });

        uzcard = new Card();
        uzcard.setId(3L);
        uzcard.setCurrency(Currency.UZS);
        when(cardRepository.findById(3L)).thenReturn(Optional.of(uzcard));
        Card dollars = new Card();
        dollars.setId(4L);
        dollars.setCurrency(Currency.USD);
        when(cardRepository.findById(4L)).thenReturn(Optional.of(dollars));

        borrowedCategory = new Category();
        borrowedCategory.setId(21L);
        borrowedCategory.setName("Borrowed");
        when(categoryRepository.findByApplicableSubTypeAndParentIsNull(TransactionSubType.LOAN_RECEIVED))
                .thenReturn(List.of(borrowedCategory));

        Counterparty uzum = new Counterparty();
        uzum.setId(50L);
        uzum.setName("Uzum Bank");
        uzum.setKind(CounterpartyKind.LENDER);
        when(counterpartyService.findOrCreate(any(), eq(CounterpartyKind.LENDER))).thenReturn(uzum);
        Counterparty mirjalol = new Counterparty();
        mirjalol.setId(60L);
        mirjalol.setName("Mirjalol Sulaymonov");
        mirjalol.setKind(CounterpartyKind.BORROWER);
        when(counterpartyService.findOrCreate(any(), eq(CounterpartyKind.BORROWER))).thenReturn(mirjalol);

        service = new FinanceService(mock(DebtRepository.class), loanGivenRepository, loanTakenRepository,
                mock(BankLoanRepository.class), mock(MonthlyPaymentRepository.class), mock(DonationRepository.class),
                mock(InvestmentRepository.class), categoryRepository, transactionRepository, cardRepository,
                mock(MarkPaidRepository.class), cardService, monthCloseService, settingsService, counterpartyService);
    }

    private static LoanTakenRequest borrowed(Boolean moveMoney, Long cardId) {
        LoanTakenRequest r = new LoanTakenRequest();
        r.setLenderName("Uzum Bank");
        r.setTotalAmount(new BigDecimal("1155000"));
        r.setCurrency(Currency.UZS);
        r.setBorrowedDate(SEP_14);
        r.setPlannedMonthlyPayment(new BigDecimal("200000"));
        r.setMoveMoney(moveMoney);
        r.setCardId(cardId);
        return r;
    }

    private static LoanGivenRequest lent(Boolean moveMoney, Long cardId) {
        LoanGivenRequest r = new LoanGivenRequest();
        r.setDebtorName("Mirjalol Sulaymonov");
        r.setTotalAmount(new BigDecimal("300000"));
        r.setCurrency(Currency.UZS);
        r.setLentDate(SEP_14);
        r.setMoveMoney(moveMoney);
        r.setCardId(cardId);
        return r;
    }

    /** Into the card: an INCOME the card's balance adds up, and the loan is that transaction's — its type, plan and lender kept. */
    @Test
    void borrowedMoneyIntoACardBooksTheTransactionTheLoanComesFrom() {
        LoanTakenResponse loan = service.createLoanTaken(borrowed(true, 3L));

        assertThat(transactions).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(TransactionType.INCOME);
            assertThat(t.getSubType()).isEqualTo(TransactionSubType.LOAN_RECEIVED);
            assertThat(t.getAmount()).isEqualByComparingTo("1155000");
            assertThat(t.getTransactionDate()).isEqualTo(SEP_14);
            assertThat(t.getCard()).isSameAs(uzcard);
            assertThat(t.getCashAmount()).isEqualByComparingTo("0");
            assertThat(t.getDescription()).isEqualTo("Uzum Bank");
            assertThat(t.getCategory()).isSameAs(borrowedCategory);
        });
        LoanTaken saved = loanTakenSaved();
        assertThat(saved.getOriginatingTransactionId()).isEqualTo(transactions.getFirst().getId());
        assertThat(loan.getRepaymentType()).isEqualTo(RepaymentType.MONTHLY);
        assertThat(loan.getPlannedMonthlyPayment()).isEqualByComparingTo("200000");
        assertThat(loan.getLenderId()).isEqualTo(50L);
        verify(settingsService).assertStableIncomeSet();
        verify(monthCloseService).assertMonthOpen(SEP_14);
        verify(cardService, never()).assertSufficientBalance(any(), any());   // money coming in
    }

    /** Out of cash: the whole amount is cash; lent money keeps its borrower. */
    @Test
    void lentMoneyFromCashBooksACashExpense() {
        LoanGivenResponse loan = service.createLoanGiven(lent(true, null));

        assertThat(transactions).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(TransactionType.EXPENSE);
            assertThat(t.getSubType()).isEqualTo(TransactionSubType.LOAN_GIVEN);
            assertThat(t.getCard()).isNull();
            assertThat(t.getCashAmount()).isEqualByComparingTo("300000");
            assertThat(t.getDescription()).isEqualTo("Mirjalol Sulaymonov");
        });
        assertThat(loan.getBorrowerId()).isEqualTo(60L);
    }

    /** Out of a card: there must be enough on it, as for every expense; a card in another currency is refused. */
    @Test
    void lentMoneyFromACardNeedsEnoughOnItAndTheRightCurrency() {
        service.createLoanGiven(lent(true, 3L));
        verify(cardService).assertSufficientBalance(uzcard, new BigDecimal("300000"));

        doThrow(new IllegalArgumentException("Insufficient card balance"))
                .when(cardService).assertSufficientBalance(any(), any());
        assertThatThrownBy(() -> service.createLoanGiven(lent(true, 3L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createLoanTaken(borrowed(true, 4L))).isInstanceOf(IllegalArgumentException.class);
    }

    /** No flag — the bot — is the record alone, as before, whatever else is sent. */
    @Test
    void withoutMoveMoneyNoWalletMoves() {
        service.createLoanTaken(borrowed(null, 3L));
        service.createLoanGiven(lent(false, null));

        assertThat(transactions).isEmpty();
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /** Deleting the loan takes its transaction — the money goes back — as deleting the transaction takes the loan. */
    @Test
    void deletingTheLoanDeletesItsTransaction() {
        service.createLoanTaken(borrowed(true, 3L));
        LoanTaken saved = loanTakenSaved();
        when(loanTakenRepository.findById(saved.getId())).thenReturn(Optional.of(saved));
        Transaction tx = transactions.getFirst();

        service.deleteLoanTaken(saved.getId());

        verify(transactionRepository).delete(tx);
        verify(loanTakenRepository).delete(saved);
    }

    /**
     * Editing the amount or date moves the transaction with it, as editing the transaction moves the
     * loan — except for money lent again on top, when the total is more than that one transaction.
     */
    @Test
    void editingTheAmountMovesTheTransaction() {
        service.createLoanTaken(borrowed(true, 3L));
        LoanTaken saved = loanTakenSaved();
        when(loanTakenRepository.findById(saved.getId())).thenReturn(Optional.of(saved));
        LoanTakenRequest edit = borrowed(null, null);
        edit.setTotalAmount(new BigDecimal("1300000"));

        service.updateLoanTaken(saved.getId(), edit);

        assertThat(transactions.getFirst().getAmount()).isEqualByComparingTo("1300000");
        assertThat(transactions.getFirst().getCashAmount()).isEqualByComparingTo("0");   // still all on the card

        service.createLoanGiven(lent(true, null));
        LoanGiven given = loanGivenSaved();
        when(loanGivenRepository.findById(given.getId())).thenReturn(Optional.of(given));
        when(transactionRepository.existsByLoanGivenId(given.getId())).thenReturn(true);   // lent again on top
        LoanGivenRequest more = lent(null, null);
        more.setTotalAmount(new BigDecimal("800000"));

        service.updateLoanGiven(given.getId(), more);

        assertThat(transactions.getLast().getAmount()).isEqualByComparingTo("300000");
    }

    private LoanTaken loanTakenSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(LoanTaken.class);
        verify(loanTakenRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private LoanGiven loanGivenSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(LoanGiven.class);
        verify(loanGivenRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
