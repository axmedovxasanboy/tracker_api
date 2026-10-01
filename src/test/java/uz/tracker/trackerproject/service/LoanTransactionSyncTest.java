package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uz.tracker.trackerproject.dto.request.BalanceTransferRequest;
import uz.tracker.trackerproject.dto.request.InvestmentContributeRequest;
import uz.tracker.trackerproject.dto.request.MonthlyPaymentPayRequest;
import uz.tracker.trackerproject.dto.request.RepaymentRequest;
import uz.tracker.trackerproject.dto.request.TransactionRequest;
import uz.tracker.trackerproject.dto.response.TransactionResponse;
import uz.tracker.trackerproject.entity.*;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A loan and the transactions that pay it stay in step: paying adds to the loan's paid figure, so
 * deleting or editing that payment — from History, the bot or Wallets — must move the figure back.
 * It did not, and the owner's "Ota-onam" loan was left 500,000 "paid" with nothing behind it and no
 * way to delete it. Also pinned here: the delete rule that counts real payments, not a paid figure,
 * and the other records a transaction moves (a holding's value, the Emergency tab's row, a bill's
 * next due date, the other half of a move between wallets).
 *
 * <p>Everything real runs — TransactionService and FinanceService — over repositories kept in memory.
 */
class LoanTransactionSyncTest {

    private static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
    private static final LocalDate SEP_20 = LocalDate.of(2026, 9, 20);

    private final Map<Long, Transaction> transactions = new LinkedHashMap<>();
    private final List<LoanTaken> loans = new ArrayList<>();
    private final List<Debt> debts = new ArrayList<>();
    private final List<LoanGiven> given = new ArrayList<>();
    private final List<Investment> holdings = new ArrayList<>();
    private final List<Emergency> emergencies = new ArrayList<>();
    private final List<MonthlyPayment> bills = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private long nextId = 100;

    private FinanceService finance;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(nextId++);
            transactions.put(t.getId(), t);
            return t;
        });
        when(transactionRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(transactions.get(inv.<Long>getArgument(0))));
        doAnswer(inv -> transactions.remove(inv.<Transaction>getArgument(0).getId()))
                .when(transactionRepository).delete(any(Transaction.class));
        when(transactionRepository.findByRepaidLoanTakenIdOrderByTransactionDateDesc(any()))
                .thenAnswer(inv -> rows(t -> Objects.equals(t.getRepaidLoanTakenId(), inv.getArgument(0))));
        when(transactionRepository.findByRepaidDebtIdOrderByTransactionDateDesc(any()))
                .thenAnswer(inv -> rows(t -> Objects.equals(t.getRepaidDebtId(), inv.getArgument(0))));
        when(transactionRepository.findByRepaidLoanGivenIdOrderByTransactionDateDesc(any()))
                .thenAnswer(inv -> rows(t -> Objects.equals(t.getRepaidLoanGivenId(), inv.getArgument(0))));
        when(transactionRepository.findByMonthlyPaymentIdOrderByTransactionDateDesc(any()))
                .thenAnswer(inv -> rows(t -> Objects.equals(t.getMonthlyPaymentId(), inv.getArgument(0))));
        when(transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(any()))
                .thenAnswer(inv -> rows(t -> Objects.equals(t.getInvestmentId(), inv.getArgument(0))));
        when(transactionRepository.findFirstByTransferPairIdAndIdNot(any(), any())).thenAnswer(inv ->
                transactions.values().stream().filter(t -> Objects.equals(t.getTransferPairId(), inv.getArgument(0))
                        && !Objects.equals(t.getId(), inv.getArgument(1))).findFirst());

        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        stub(loanTakenRepository, loans, LoanTaken::getId, LoanTaken::setId);
        when(loanTakenRepository.findByOriginatingTransactionId(any())).thenAnswer(inv -> loans.stream()
                .filter(l -> Objects.equals(l.getOriginatingTransactionId(), inv.getArgument(0))).findFirst());
        DebtRepository debtRepository = mock(DebtRepository.class);
        stub(debtRepository, debts, Debt::getId, Debt::setId);
        LoanGivenRepository loanGivenRepository = mock(LoanGivenRepository.class);
        stub(loanGivenRepository, given, LoanGiven::getId, LoanGiven::setId);
        when(loanGivenRepository.findByOriginatingTransactionId(any())).thenAnswer(inv -> given.stream()
                .filter(l -> Objects.equals(l.getOriginatingTransactionId(), inv.getArgument(0))).findFirst());
        InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
        stub(investmentRepository, holdings, Investment::getId, Investment::setId);
        when(investmentRepository.findByOriginatingTransactionId(any())).thenReturn(Optional.empty());
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        stub(monthlyPaymentRepository, bills, MonthlyPayment::getId, MonthlyPayment::setId);
        EmergencyRepository emergencyRepository = mock(EmergencyRepository.class);
        when(emergencyRepository.findByOriginatingTransactionId(any())).thenAnswer(inv -> emergencies.stream()
                .filter(e -> Objects.equals(e.getOriginatingTransactionId(), inv.getArgument(0))).findFirst());
        doAnswer(inv -> emergencies.remove(inv.<Emergency>getArgument(0))).when(emergencyRepository).delete(any(Emergency.class));
        when(emergencyRepository.save(any(Emergency.class))).thenAnswer(inv -> inv.getArgument(0));
        CardRepository cardRepository = mock(CardRepository.class);
        when(cardRepository.findById(any())).thenAnswer(inv -> cards.stream()
                .filter(c -> c.getId().equals(inv.getArgument(0))).findFirst());
        when(cardRepository.sumTransactionsByCardId(any())).thenAnswer(inv -> transactions.values().stream()
                .filter(t -> t.getCard() != null && t.getCard().getId().equals(inv.getArgument(0)))
                .map(t -> t.getType() == TransactionType.INCOME ? t.getAmount() : t.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        finance = new FinanceService(debtRepository, loanGivenRepository, loanTakenRepository,
                mock(BankLoanRepository.class), monthlyPaymentRepository, mock(DonationRepository.class),
                investmentRepository, mock(CategoryRepository.class), transactionRepository, cardRepository,
                mock(MarkPaidRepository.class), mock(CardService.class), mock(MonthCloseService.class),
                mock(SettingsService.class), mock(CounterpartyService.class));
        service = new TransactionService(transactionRepository, mock(CategoryRepository.class), cardRepository,
                mock(CashBalanceRepository.class), finance, mock(MonthCloseService.class), mock(SettingsService.class),
                loanGivenRepository, loanTakenRepository, mock(DonationRepository.class), investmentRepository);
        ReflectionTestUtils.setField(service, "emergencyRepository", emergencyRepository);
    }

    /** An in-memory JpaRepository over {@code rows}. */
    private <T> void stub(org.springframework.data.jpa.repository.JpaRepository<T, Long> repo, List<T> rows,
                          Function<T, Long> id, java.util.function.BiConsumer<T, Long> setId) {
        when(repo.findById(any())).thenAnswer(inv -> rows.stream().filter(r -> Objects.equals(id.apply(r), inv.getArgument(0))).findFirst());
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(rows));
        when(repo.existsById(any())).thenAnswer(inv -> rows.stream().anyMatch(r -> Objects.equals(id.apply(r), inv.getArgument(0))));
        when(repo.save(any())).thenAnswer(inv -> {
            T row = inv.getArgument(0);
            if (id.apply(row) == null) setId.accept(row, nextId++);
            if (!rows.contains(row)) rows.add(row);
            return row;
        });
        doAnswer(inv -> rows.remove(inv.<T>getArgument(0))).when(repo).delete(any());
    }

    private List<Transaction> rows(Predicate<Transaction> p) {
        List<Transaction> found = new ArrayList<>(transactions.values().stream().filter(p).toList());
        found.sort(Comparator.comparing(Transaction::getTransactionDate).reversed());
        return found;
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private LoanTaken loan(long id, String lender, String total, String paid) {
        LoanTaken l = new LoanTaken();
        l.setId(id);
        l.setLenderName(lender);
        l.setTotalAmount(n(total));
        l.setPaidAmount(n(paid));
        l.setCurrency(Currency.UZS);
        l.setBorrowedDate(LocalDate.of(2026, 6, 1));
        l.setStatus(n(paid).signum() > 0 ? RecordStatus.PARTIALLY_PAID : RecordStatus.PENDING);
        loans.add(l);
        return l;
    }

    private Debt debt(long id, String total) {
        Debt d = new Debt();
        d.setId(id);
        d.setCreditorName("Do'kon");
        d.setTotalAmount(n(total));
        d.setPaidAmount(BigDecimal.ZERO);
        d.setCurrency(Currency.UZS);
        d.setBorrowedDate(LocalDate.of(2026, 6, 1));
        d.setStatus(RecordStatus.PENDING);
        debts.add(d);
        return d;
    }

    private LoanGiven lent(long id, String total) {
        LoanGiven l = new LoanGiven();
        l.setId(id);
        l.setDebtorName("Do'stim");
        l.setTotalAmount(n(total));
        l.setReceivedAmount(BigDecimal.ZERO);
        l.setCurrency(Currency.UZS);
        l.setLentDate(LocalDate.of(2026, 6, 1));
        l.setStatus(RecordStatus.PENDING);
        given.add(l);
        return l;
    }

    private static RepaymentRequest pay(String amount, LocalDate on) {
        RepaymentRequest r = new RepaymentRequest();
        r.setAmount(n(amount));
        r.setPaymentDate(on);
        return r;
    }

    /** The row Pay wrote, as the newest transaction naming the loan. */
    private Transaction lastRow() {
        return transactions.values().stream().reduce((a, b) -> b).orElseThrow();
    }

    /** An edit as History or the bot sends it: the row's own fields, none of the loan keys unless set. */
    private static TransactionRequest edit(Transaction t) {
        TransactionRequest r = new TransactionRequest();
        r.setType(t.getType());
        r.setAmount(t.getAmount());
        r.setCurrency(t.getCurrency());
        r.setDescription(t.getDescription());
        r.setTransactionDate(t.getTransactionDate());
        r.setSubType(t.getSubType());
        r.setCardId(t.getCard() == null ? null : t.getCard().getId());
        r.setCashAmount(t.getCard() == null ? null : t.getCashAmount());   // a cash row is all cash, whatever its amount
        return r;
    }

    // ── 1. A payment deleted or edited moves the loan with it ─────────────────

    @Test
    void deletingARepaymentGivesTheBorrowedLoanItsMoneyBack() {
        LoanTaken parents = loan(1, "Ota-onam", "50000000", "0");
        finance.repayLoanTaken(1L, pay("500000", SEP_10));
        assertThat(parents.getPaidAmount()).isEqualByComparingTo("500000");
        assertThat(parents.getStatus()).isEqualTo(RecordStatus.PARTIALLY_PAID);

        service.delete(lastRow().getId());

        assertThat(parents.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(parents.getStatus()).isEqualTo(RecordStatus.PENDING);
        assertThat(uz.tracker.trackerproject.dto.response.LoanTakenResponse.from(parents).getRemainingAmount())
                .isEqualByComparingTo("50000000");
        // …and now nothing stands in the way of deleting it, which is what the owner wanted.
        finance.deleteLoanTaken(1L);
        assertThat(loans).isEmpty();
    }

    @Test
    void deletingAPaymentThatClearedALoanReopensIt() {
        LoanTaken uzum = loan(8, "Uzum Nasiya", "800000", "0");
        finance.repayLoanTaken(8L, pay("800000", SEP_10));
        assertThat(uzum.getStatus()).isEqualTo(RecordStatus.PAID);

        service.delete(lastRow().getId());

        assertThat(uzum.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(uzum.getStatus()).isEqualTo(RecordStatus.PENDING);
    }

    @Test
    void deletingADebtRepaymentGivesTheDebtItsMoneyBack() {
        Debt shop = debt(3, "900000");
        finance.repayDebt(3L, pay("300000", SEP_10));
        finance.repayDebt(3L, pay("200000", SEP_20));
        assertThat(shop.getPaidAmount()).isEqualByComparingTo("500000");

        service.delete(lastRow().getId());

        assertThat(shop.getPaidAmount()).isEqualByComparingTo("300000");
        assertThat(shop.getStatus()).isEqualTo(RecordStatus.PARTIALLY_PAID);
        assertThat(uz.tracker.trackerproject.dto.response.DebtResponse.from(shop).getRemainingAmount())
                .isEqualByComparingTo("600000");
    }

    @Test
    void deletingMoneyBackFromABorrowerPutsItBackOnWhatTheyOwe() {
        LoanGiven friend = lent(5, "1000000");
        finance.markLoanGivenReturned(5L, pay("1000000", SEP_10));
        assertThat(friend.getStatus()).isEqualTo(RecordStatus.PAID);

        service.delete(lastRow().getId());

        assertThat(friend.getReceivedAmount()).isEqualByComparingTo("0");
        assertThat(friend.getStatus()).isEqualTo(RecordStatus.PENDING);
    }

    @Test
    void editingARepaymentsAmountMovesThePaidFigure_andMoreThanIsLeftIsRefused() {
        LoanTaken parents = loan(1, "Ota-onam", "1000000", "0");
        finance.repayLoanTaken(1L, pay("500000", SEP_10));
        Transaction row = lastRow();

        TransactionRequest less = edit(row);
        less.setAmount(n("300000"));
        service.update(row.getId(), less);
        assertThat(parents.getPaidAmount()).isEqualByComparingTo("300000");

        TransactionRequest all = edit(row);
        all.setAmount(n("1000000"));
        service.update(row.getId(), all);
        assertThat(parents.getPaidAmount()).isEqualByComparingTo("1000000");
        assertThat(parents.getStatus()).isEqualTo(RecordStatus.PAID);

        TransactionRequest tooMuch = edit(row);
        tooMuch.setAmount(n("1200000"));
        assertThatThrownBy(() -> service.update(row.getId(), tooMuch))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds remaining balance");
    }

    @Test
    void movingARepaymentToAnotherLoanOrToADebtLeavesBothRight() {
        LoanTaken a = loan(1, "Ota-onam", "50000000", "0");
        LoanTaken b = loan(2, "Akam", "3000000", "1000000");             // 1,000,000 repaid before tracking
        Debt shop = debt(3, "900000");
        finance.repayLoanTaken(1L, pay("500000", SEP_10));
        Transaction row = lastRow();

        TransactionRequest toB = edit(row);
        toB.setRepaidLoanTakenId(2L);
        toB.setAmount(n("400000"));
        service.update(row.getId(), toB);

        assertThat(a.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(a.getStatus()).isEqualTo(RecordStatus.PENDING);
        assertThat(b.getPaidAmount()).isEqualByComparingTo("1400000");
        assertThat(row.getRepaidLoanTakenId()).isEqualTo(2L);

        TransactionRequest toDebt = edit(row);
        toDebt.setRepaidDebtId(3L);
        service.update(row.getId(), toDebt);

        assertThat(b.getPaidAmount()).isEqualByComparingTo("1000000");
        assertThat(shop.getPaidAmount()).isEqualByComparingTo("400000");
        assertThat(row.getRepaidLoanTakenId()).isNull();
        assertThat(row.getRepaidDebtId()).isEqualTo(3L);
        assertThat(finance.getLoanTakenRepayments(2L)).isEmpty();
        assertThat(finance.getDebtRepayments(3L)).extracting(TransactionResponse::getId).containsExactly(row.getId());
    }

    @Test
    void aRepaymentRefiledAsSomethingElsePaysNoLoanAnyMore() {
        LoanTaken parents = loan(1, "Ota-onam", "50000000", "0");
        finance.repayLoanTaken(1L, pay("500000", SEP_10));
        Transaction row = lastRow();

        TransactionRequest everyday = edit(row);
        everyday.setSubType(TransactionSubType.REGULAR_EXPENSE);
        service.update(row.getId(), everyday);

        assertThat(parents.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(row.getRepaidLoanTakenId()).isNull();
        assertThat(finance.getLoanTakenRepayments(1L)).isEmpty();
    }

    /** The bot edits a row by echoing the fields it knows — no loan keys: the link and the paid figure stay. */
    @Test
    void anEditThatDoesNotMentionTheLoanKeepsIt_andMovesNothing() {
        LoanTaken parents = loan(1, "Ota-onam", "50000000", "0");
        finance.repayLoanTaken(1L, pay("500000", SEP_10));
        parents.setStatus(RecordStatus.OVERDUE);                          // set by hand: an edit of the text leaves it
        Transaction row = lastRow();

        TransactionRequest text = edit(row);
        text.setDescription("Onamga, sentabr");
        service.update(row.getId(), text);

        assertThat(row.getRepaidLoanTakenId()).isEqualTo(1L);
        assertThat(parents.getPaidAmount()).isEqualByComparingTo("500000");
        assertThat(parents.getStatus()).isEqualTo(RecordStatus.OVERDUE);
    }

    @Test
    void aPaymentRecordedOnHistoryThatNamesItsLoanPaysIt() {
        LoanTaken parents = loan(1, "Ota-onam", "50000000", "0");
        TransactionRequest r = new TransactionRequest();
        r.setType(TransactionType.EXPENSE);
        r.setSubType(TransactionSubType.LOAN_REPAYMENT);
        r.setAmount(n("500000"));
        r.setCurrency(Currency.UZS);
        r.setDescription("Onamga");
        r.setTransactionDate(SEP_10);
        r.setRepaidLoanTakenId(1L);

        TransactionResponse created = service.create(r);

        assertThat(created.getRepaidLoanTakenId()).isEqualTo(1L);
        assertThat(parents.getPaidAmount()).isEqualByComparingTo("500000");

        TransactionRequest both = new TransactionRequest();
        both.setRepaidLoanTakenId(1L);
        both.setRepaidDebtId(3L);
        both.setType(TransactionType.EXPENSE);
        both.setSubType(TransactionSubType.LOAN_REPAYMENT);
        both.setAmount(n("1"));
        both.setCurrency(Currency.UZS);
        both.setTransactionDate(SEP_10);
        assertThatThrownBy(() -> service.create(both)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A payment pays one loan or one debt, not both.");
    }

    // ── 2. The delete rule counts payments in History, not a paid figure ──────

    /** The owner's case: 500,000 "paid" with no transaction behind it. */
    @Test
    void aLoanWithAPaidAmountButNoPaymentsCanBeDeleted_andTakesItsOwnRowWithIt() {
        LoanTaken parents = loan(1, "Ota-onam", "50000000", "500000");
        Transaction mirror = new Transaction();
        mirror.setType(TransactionType.INCOME);
        mirror.setSubType(TransactionSubType.LOAN_RECEIVED);
        mirror.setAmount(n("50000000"));
        mirror.setCurrency(Currency.UZS);
        mirror.setTransactionDate(LocalDate.of(2026, 6, 1));
        mirror.setDescription("Ota-onam");
        mirror.setId(77L);
        transactions.put(77L, mirror);
        parents.setOriginatingTransactionId(77L);

        finance.deleteLoanTaken(1L);

        assertThat(loans).isEmpty();
        assertThat(transactions).doesNotContainKey(77L);
    }

    @Test
    void aLoanWithPaymentsInHistoryIsNotDeleted_andTheMessageSaysWhatToDo() {
        loan(1, "Ota-onam", "50000000", "0");
        finance.repayLoanTaken(1L, pay("500000", SEP_10));
        assertThatThrownBy(() -> finance.deleteLoanTaken(1L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This loan has 1 payment in History. Delete it first, or keep the loan.");

        finance.repayLoanTaken(1L, pay("500000", SEP_20));
        assertThatThrownBy(() -> finance.deleteLoanTaken(1L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This loan has 2 payments in History. Delete those first, or keep the loan.");
        assertThat(loans).hasSize(1);
    }

    @Test
    void theSameRuleForADebtAndForALoanGiven() {
        Debt typedPaid = debt(3, "900000");
        typedPaid.setPaidAmount(n("400000"));                             // typed by hand, nothing in History
        finance.deleteDebt(3L);
        assertThat(debts).isEmpty();

        debt(4, "900000");
        finance.repayDebt(4L, pay("100000", SEP_10));
        assertThatThrownBy(() -> finance.deleteDebt(4L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This debt has 1 payment in History. Delete it first, or keep the debt.");

        LoanGiven typedBack = lent(5, "1000000");
        typedBack.setReceivedAmount(n("300000"));
        finance.deleteLoanGiven(5L);
        assertThat(given).isEmpty();

        lent(6, "1000000");
        finance.markLoanGivenReturned(6L, pay("200000", SEP_10));
        finance.markLoanGivenReturned(6L, pay("300000", SEP_20));
        assertThatThrownBy(() -> finance.deleteLoanGiven(6L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This loan has 2 payments back to you in History. Delete those first, or keep the loan.");
    }

    /** Deleting the row a loan came from deletes the loan — so the same rule applies there. */
    @Test
    void theRowALoanCameFromCannotTakeItWhilePaymentsForItAreInHistory() {
        TransactionRequest borrowed = new TransactionRequest();
        borrowed.setType(TransactionType.INCOME);
        borrowed.setSubType(TransactionSubType.LOAN_RECEIVED);
        borrowed.setAmount(n("1000000"));
        borrowed.setCurrency(Currency.UZS);
        borrowed.setDescription("Akam");
        borrowed.setTransactionDate(SEP_10);
        Long rowId = service.create(borrowed).getId();
        LoanTaken loan = loans.getFirst();
        finance.repayLoanTaken(loan.getId(), pay("200000", SEP_20));
        Transaction payment = lastRow();

        assertThatThrownBy(() -> service.delete(rowId)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This loan has 1 payment in History. Delete it first, or keep the loan.");

        service.delete(payment.getId());
        service.delete(rowId);
        assertThat(loans).isEmpty();
    }

    // ── 3. The other records a transaction moves ──────────────────────────────

    /** Put in raises a tracked value; deleting that Put in from History now lowers it again. */
    @Test
    void aContributionDeletedFromHistoryTakesItsMoneyOutOfTheValueToo() {
        Investment iman = new Investment();
        iman.setId(40L);
        iman.setName("IMAN");
        iman.setType(InvestmentType.OTHER);
        iman.setInvestedAmount(n("5000000"));
        iman.setCurrentValue(n("5600000"));
        iman.setCurrency(Currency.UZS);
        iman.setPurchaseDate(LocalDate.of(2026, 3, 1));
        holdings.add(iman);
        InvestmentContributeRequest putIn = new InvestmentContributeRequest();
        putIn.setAmount(n("1000000"));
        putIn.setCurrency(Currency.UZS);
        putIn.setDate(SEP_10);
        finance.contributeToInvestment(40L, putIn);
        assertThat(iman.getCurrentValue()).isEqualByComparingTo("6600000");

        service.delete(lastRow().getId());

        assertThat(iman.getInvestedAmount()).isEqualByComparingTo("5000000");
        assertThat(iman.getCurrentValue()).isEqualByComparingTo("5600000");

        // And a top-up recorded on the Transactions page raises it the same way Put in does.
        TransactionRequest topUp = new TransactionRequest();
        topUp.setType(TransactionType.EXPENSE);
        topUp.setSubType(TransactionSubType.INVESTMENT);
        topUp.setInvestmentId(40L);
        topUp.setAmount(n("300000"));
        topUp.setCurrency(Currency.UZS);
        topUp.setTransactionDate(SEP_20);
        service.create(topUp);
        assertThat(iman.getInvestedAmount()).isEqualByComparingTo("5300000");
        assertThat(iman.getCurrentValue()).isEqualByComparingTo("5900000");
    }

    /** The Emergency tab's row mirrors a transaction; editing or deleting that transaction in History moves it. */
    @Test
    void theEmergencyTabsRowFollowsItsTransaction() {
        Transaction tx = new Transaction();
        tx.setType(TransactionType.EXPENSE);
        tx.setSubType(TransactionSubType.EMERGENCY_CONTRIBUTION);
        tx.setAmount(n("720000"));
        tx.setCashAmount(n("720000"));
        tx.setCurrency(Currency.UZS);
        tx.setTransactionDate(SEP_10);
        tx.setDescription("Emergency fund contribution");
        tx.setId(88L);
        transactions.put(88L, tx);
        Emergency row = new Emergency();
        row.setAmount(n("720000"));
        row.setCurrency(Currency.UZS);
        row.setDate(SEP_10);
        row.setOriginatingTransactionId(88L);
        emergencies.add(row);

        TransactionRequest more = edit(tx);
        more.setAmount(n("800000"));
        more.setTransactionDate(SEP_20);
        service.update(88L, more);
        assertThat(row.getAmount()).isEqualByComparingTo("800000");
        assertThat(row.getDate()).isEqualTo(SEP_20);

        service.delete(88L);
        assertThat(emergencies).isEmpty();
    }

    /** A bill paid, then that payment deleted: the bill is due again in that month, not the next. */
    @Test
    void aBillsNextDueDateFollowsItsPayments() {
        MonthlyPayment rent = new MonthlyPayment();
        rent.setId(3L);
        rent.setName("Kvartira Arenda");
        rent.setAmount(n("4200000"));
        rent.setCurrency(Currency.UZS);
        rent.setDueDay(10);
        rent.setActive(true);
        bills.add(rent);
        MonthlyPaymentPayRequest pay = new MonthlyPaymentPayRequest();
        pay.setAmount(n("4200000"));
        pay.setPaymentDate(LocalDate.of(2026, 8, 9));
        pay.setMode(MonthlyPaymentPayRequest.Mode.CASH);
        finance.payMonthlyPayment(3L, pay);
        Transaction august = lastRow();
        pay.setPaymentDate(SEP_10);
        finance.payMonthlyPayment(3L, pay);
        Transaction september = lastRow();
        assertThat(rent.getNextDueDate()).isEqualTo(LocalDate.of(2026, 10, 10));

        // An older payment deleted: the next due date stays with September's.
        service.delete(august.getId());
        assertThat(rent.getNextDueDate()).isEqualTo(LocalDate.of(2026, 10, 10));

        // September's moved into October: November is next.
        TransactionRequest later = edit(september);
        later.setTransactionDate(LocalDate.of(2026, 10, 2));
        service.update(september.getId(), later);
        assertThat(rent.getNextDueDate()).isEqualTo(LocalDate.of(2026, 11, 10));

        // The only payment deleted: October, the month it paid, is due again.
        service.delete(september.getId());
        assertThat(rent.getNextDueDate()).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    /** One half of a move between wallets edited: the other half follows, so no money appears or vanishes. */
    @Test
    void editingOneHalfOfAMoveMovesTheOtherHalf() {
        cards.add(card(1L, "Uzcard", "5000000"));
        cards.add(card(2L, "Humo", "0"));
        BalanceTransferRequest move = new BalanceTransferRequest();
        move.setFromCardId(1L);
        move.setToCardId(2L);
        move.setAmount(n("1000000"));
        move.setTransactionDate(SEP_10);
        List<TransactionResponse> halves = service.transferBalance(move);
        Transaction out = transactions.get(halves.get(0).getId());
        Transaction in = transactions.get(halves.get(1).getId());

        TransactionRequest bigger = edit(in);
        bigger.setAmount(n("1500000"));
        bigger.setTransactionDate(SEP_20);
        service.update(in.getId(), bigger);

        assertThat(out.getAmount()).isEqualByComparingTo("1500000");
        assertThat(out.getTransactionDate()).isEqualTo(SEP_20);

        // The half that takes money out must still have it.
        TransactionRequest tooBig = edit(in);
        tooBig.setAmount(n("9000000"));
        assertThatThrownBy(() -> service.update(in.getId(), tooBig))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Insufficient card balance");
    }

    private static Card card(long id, String name, String initial) {
        Card c = new Card();
        c.setId(id);
        c.setName(name);
        c.setCurrency(Currency.UZS);
        c.setInitialBalance(n(initial));
        return c;
    }

    /** Money lent again, then that top-up deleted: what came back may now be all of it. */
    @Test
    void aLoanGivenWhoseTopUpIsDeletedIsPaidBackWhenWhatCameBackCoversIt() {
        LoanGiven friend = lent(5, "1000000");
        finance.markLoanGivenReturned(5L, pay("1000000", SEP_10));
        TransactionRequest again = new TransactionRequest();
        again.setType(TransactionType.EXPENSE);
        again.setSubType(TransactionSubType.LOAN_GIVEN);
        again.setLoanGivenId(5L);
        again.setAmount(n("500000"));
        again.setCurrency(Currency.UZS);
        again.setTransactionDate(SEP_20);
        Long topUp = service.create(again).getId();
        assertThat(friend.getStatus()).isEqualTo(RecordStatus.PENDING);  // lending again reopens it

        service.delete(topUp);

        assertThat(friend.getTotalAmount()).isEqualByComparingTo("1000000");
        assertThat(friend.getStatus()).isEqualTo(RecordStatus.PAID);
    }

    /** The row a borrowed loan came from re-sized: the loan's status follows what was paid against the new total. */
    @Test
    void aBorrowedLoansRowResizedMovesItsStatus() {
        TransactionRequest borrowed = new TransactionRequest();
        borrowed.setType(TransactionType.INCOME);
        borrowed.setSubType(TransactionSubType.LOAN_RECEIVED);
        borrowed.setAmount(n("1000000"));
        borrowed.setCurrency(Currency.UZS);
        borrowed.setDescription("Akam");
        borrowed.setTransactionDate(SEP_10);
        Transaction row = transactions.get(service.create(borrowed).getId());
        LoanTaken loan = loans.getFirst();
        finance.repayLoanTaken(loan.getId(), pay("600000", SEP_20));

        TransactionRequest smaller = edit(row);
        smaller.setAmount(n("600000"));
        service.update(row.getId(), smaller);

        assertThat(loan.getTotalAmount()).isEqualByComparingTo("600000");
        assertThat(loan.getStatus()).isEqualTo(RecordStatus.PAID);
    }
}
