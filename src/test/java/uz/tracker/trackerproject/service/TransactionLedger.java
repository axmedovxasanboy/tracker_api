package uz.tracker.trackerproject.service;

import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The transactions table for a test: rows are added here, and the mocked repository answers the
 * queries the advisor path makes by filtering them the way each query's JPQL does. One set of rows
 * then feeds the Plan, the salary history and the pace alike, so a fixture cannot contradict itself.
 */
final class TransactionLedger {

    private final List<Transaction> rows = new ArrayList<>();
    private long nextId = 1000;

    TransactionLedger(TransactionRepository repo) {
        when(repo.findByTransactionDateBetween(any(), any())).thenAnswer(inv ->
                select(t -> within(t, inv.getArgument(0), inv.getArgument(1))));
        when(repo.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(any(), any(), any())).thenAnswer(inv -> {
            List<Transaction> found = select(t -> t.getSubType() == inv.getArgument(0)
                    && within(t, inv.getArgument(1), inv.getArgument(2)));
            found.sort(Comparator.comparing(Transaction::getTransactionDate).reversed());
            return found;
        });
        when(repo.sumBySubTypeCurrencyDateRange(any(), any(), any(), any())).thenAnswer(inv ->
                sum(t -> t.getSubType() == inv.getArgument(0) && t.getCurrency() == inv.getArgument(1)
                        && within(t, inv.getArgument(2), inv.getArgument(3))));
        when(repo.sumByMonthlyPaymentIdAndDateRange(any(), any(), any())).thenAnswer(inv ->
                sum(t -> Objects.equals(t.getMonthlyPaymentId(), inv.getArgument(0))
                        && within(t, inv.getArgument(1), inv.getArgument(2))));
        when(repo.sumRepaymentsToLoansTaken(any(), any(), any(), any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return sum(t -> t.getSubType() == TransactionSubType.LOAN_REPAYMENT
                    && ids.contains(t.getRepaidLoanTakenId()) && t.getCurrency() == inv.getArgument(1)
                    && within(t, inv.getArgument(2), inv.getArgument(3)));
        });
        when(repo.findByRepaidLoanTakenIdOrderByTransactionDateDesc(any())).thenAnswer(inv -> {
            List<Transaction> found = select(t -> Objects.equals(t.getRepaidLoanTakenId(), inv.getArgument(0)));
            found.sort(Comparator.comparing(Transaction::getTransactionDate).reversed());
            return found;
        });
        when(repo.findByRepaidDebtIdOrderByTransactionDateDesc(any())).thenAnswer(inv -> {
            List<Transaction> found = select(t -> Objects.equals(t.getRepaidDebtId(), inv.getArgument(0)));
            found.sort(Comparator.comparing(Transaction::getTransactionDate).reversed());
            return found;
        });
        when(repo.sumBonusIncomeByCurrencyDateRange(any(), any(), any())).thenAnswer(inv ->
                sum(t -> t.getType() == TransactionType.INCOME && t.getCurrency() == inv.getArgument(0)
                        && within(t, inv.getArgument(1), inv.getArgument(2)) && bonus(t.getCategory())));
    }

    /** One UZS row; the caller sets whatever link the row carries (a bill, a loan, a transfer pair). */
    Transaction add(LocalDate date, TransactionType type, TransactionSubType subType, String amount) {
        Transaction t = new Transaction();
        t.setId(nextId++);
        t.setTransactionDate(date);
        t.setType(type);
        t.setSubType(subType);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency(Currency.UZS);
        t.setDescription(subType == null ? "" : subType.name());
        rows.add(t);
        return t;
    }

    Transaction income(LocalDate date, String amount, Category category) {
        Transaction t = add(date, TransactionType.INCOME, TransactionSubType.REGULAR_INCOME, amount);
        t.setCategory(category);
        return t;
    }

    Transaction expense(LocalDate date, String amount) {
        return add(date, TransactionType.EXPENSE, TransactionSubType.REGULAR_EXPENSE, amount);
    }

    /** A bill paid through Pay: a regular expense linked to the bill. */
    Transaction billPaid(LocalDate date, String amount, long billId) {
        Transaction t = expense(date, amount);
        t.setMonthlyPaymentId(billId);
        return t;
    }

    static Category category(String name, boolean bonus, Category parent) {
        Category c = new Category();
        c.setName(name);
        c.setBonusIncome(bonus);
        c.setParent(parent);
        return c;
    }

    private List<Transaction> select(Predicate<Transaction> p) {
        return new ArrayList<>(rows.stream().filter(p).toList());
    }

    private BigDecimal sum(Predicate<Transaction> p) {
        return rows.stream().filter(p).map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean within(Transaction t, LocalDate start, LocalDate end) {
        return !t.getTransactionDate().isBefore(start) && !t.getTransactionDate().isAfter(end);
    }

    private static boolean bonus(Category c) {
        return c != null && (Boolean.TRUE.equals(c.getBonusIncome())
                || (c.getParent() != null && Boolean.TRUE.equals(c.getParent().getBonusIncome())));
    }
}
