package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    /**
     * Sum of amount by type+currency, EXCLUDING transfer halves and money taken out of an
     * investment. Both just move the owner's own money and shouldn't inflate income totals.
     */
    @Query("""
            SELECT SUM(t.amount)
            FROM Transaction t
            WHERE t.type = :type AND t.currency = :currency
              AND (t.subType IS NULL OR t.subType NOT IN (
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_IN,
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_OUT,
                    uz.tracker.trackerproject.enums.TransactionSubType.INVESTMENT_WITHDRAWAL))
            """)
    BigDecimal sumByTypeAndCurrency(@Param("type") TransactionType type,
                                    @Param("currency") Currency currency);

    @Query("""
            SELECT COUNT(t) FROM Transaction t
            WHERE t.currency = :currency
              AND (t.subType IS NULL OR t.subType NOT IN (
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_IN,
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_OUT))
            """)
    long countByCurrency(@Param("currency") Currency currency);

    /**
     * Sum amount by type + currency restricted to a date range, with transfer/exchange
     * halves excluded — used by the Overview page to compute "earned this month" for
     * each currency separately (so the service can FX-convert and total).
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.type = :type AND t.currency = :currency
              AND t.transactionDate >= :start AND t.transactionDate <= :end
              AND (t.subType IS NULL OR t.subType NOT IN (
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_IN,
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_OUT,
                    uz.tracker.trackerproject.enums.TransactionSubType.EVERYDAY_SPENDING,
                    uz.tracker.trackerproject.enums.TransactionSubType.INVESTMENT_WITHDRAWAL))
            """)
    BigDecimal sumByTypeCurrencyDateRange(
            @Param("type") TransactionType type,
            @Param("currency") Currency currency,
            @Param("start") java.time.LocalDate start,
            @Param("end") java.time.LocalDate end);

    /**
     * Sum amount by exact sub-type + currency + date range. Used by the Overview
     * action-item progress bars (bank installments paid this month, personal-loan
     * repayments paid this month).
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.subType = :subType AND t.currency = :currency
              AND t.transactionDate >= :start AND t.transactionDate <= :end
            """)
    BigDecimal sumBySubTypeCurrencyDateRange(
            @Param("subType") uz.tracker.trackerproject.enums.TransactionSubType subType,
            @Param("currency") Currency currency,
            @Param("start") java.time.LocalDate start,
            @Param("end") java.time.LocalDate end);

    /**
     * LOAN_REPAYMENT money paid in a date range toward the given borrowed loans. Used to tell the
     * repayments that fund a MONTHLY loan's plan apart from the ones that pay back everything ASAP —
     * the Plan asks for the two separately, so it must count them separately. Callers must not pass
     * an empty collection.
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.subType = uz.tracker.trackerproject.enums.TransactionSubType.LOAN_REPAYMENT
              AND t.repaidLoanTakenId IN :loanTakenIds
              AND t.currency = :currency
              AND t.transactionDate >= :start AND t.transactionDate <= :end
            """)
    BigDecimal sumRepaymentsToLoansTaken(
            @Param("loanTakenIds") java.util.Collection<Long> loanTakenIds,
            @Param("currency") Currency currency,
            @Param("start") java.time.LocalDate start,
            @Param("end") java.time.LocalDate end);

    /**
     * Net amount of one sub-type in a date range: EXPENSE rows count up, INCOME rows count down.
     * Used for EVERYDAY_SPENDING, where a reconciliation books untracked spending as an expense and
     * a surplus it found as income — the running figure is the difference.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN t.type = :expense THEN t.amount ELSE -t.amount END), 0)
            FROM Transaction t
            WHERE t.subType = :subType AND t.currency = :currency
              AND t.transactionDate >= :start AND t.transactionDate <= :end
            """)
    BigDecimal netEverydaySpend(
            @Param("subType") uz.tracker.trackerproject.enums.TransactionSubType subType,
            @Param("expense") uz.tracker.trackerproject.enums.TransactionType expense,
            @Param("currency") Currency currency,
            @Param("start") java.time.LocalDate start,
            @Param("end") java.time.LocalDate end);

    /**
     * Sum of INCOME tagged as "bonus" in a month + currency. A transaction counts when
     * its own category — or that category's parent — has bonusIncome = true (so flagging
     * a parent like "Salary" covers all its children). Drives the allocation top-up.
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            LEFT JOIN t.category c
            LEFT JOIN c.parent p
            WHERE t.type = uz.tracker.trackerproject.enums.TransactionType.INCOME
              AND t.currency = :currency
              AND t.transactionDate >= :start AND t.transactionDate <= :end
              AND (c.bonusIncome = true OR p.bonusIncome = true)
              AND (t.subType IS NULL OR t.subType <> uz.tracker.trackerproject.enums.TransactionSubType.INVESTMENT_WITHDRAWAL)
            """)
    BigDecimal sumBonusIncomeByCurrencyDateRange(
            @Param("currency") Currency currency,
            @Param("start") java.time.LocalDate start,
            @Param("end") java.time.LocalDate end);

    /** Repayment lookup helpers — newest payment first for the per-loan history view. */
    List<Transaction> findByRepaidLoanTakenIdOrderByTransactionDateDesc(Long loanTakenId);
    List<Transaction> findByRepaidLoanGivenIdOrderByTransactionDateDesc(Long loanGivenId);
    /** Whether money was lent again on top of loan {@code loanGivenId} ("he asked again" top-ups). */
    boolean existsByLoanGivenId(Long loanGivenId);
    List<Transaction> findByRepaidDebtIdOrderByTransactionDateDesc(Long debtId);

    /** Subscription payment history + aggregates (per MonthlyPayment). */
    List<Transaction> findByMonthlyPaymentIdOrderByTransactionDateDesc(Long monthlyPaymentId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.monthlyPaymentId = :id")
    BigDecimal sumAmountByMonthlyPaymentId(@Param("id") Long monthlyPaymentId);

    /** Subscription payments recorded for one MonthlyPayment within a date window (this month). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.monthlyPaymentId = :id " +
            "AND t.transactionDate BETWEEN :start AND :end")
    BigDecimal sumByMonthlyPaymentIdAndDateRange(@Param("id") Long id,
                                                 @Param("start") java.time.LocalDate start,
                                                 @Param("end") java.time.LocalDate end);

    /**
     * Every transaction dated within a window, whatever its kind. The daily advice reads the last
     * few weeks with it and decides in code which rows were everyday spending, so the rule lives in
     * one readable place (see DailyAdviceService.isEverydaySpend) rather than in a query.
     */
    List<Transaction> findByTransactionDateBetween(java.time.LocalDate start, java.time.LocalDate end);

    /** Transactions of one sub-type within a date window — used for the Stocks bucket history. */
    List<Transaction> findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
            uz.tracker.trackerproject.enums.TransactionSubType subType,
            java.time.LocalDate start, java.time.LocalDate end);

    long countByMonthlyPaymentId(Long monthlyPaymentId);

    // Suggestions for autocomplete — uses ILIKE (PostgreSQL case-insensitive LIKE).
    // When categoryId is non-null, scoped to that exact category (leaf sub-category),
    // not its parent — recommendations are per-sub-category by design.
    @Query(value = """
            SELECT DISTINCT description FROM transactions
            WHERE description ILIKE :prefix || '%'
              AND (CAST(:categoryId AS bigint) IS NULL OR category_id = :categoryId)
            ORDER BY description
            LIMIT 10
            """, nativeQuery = true)
    List<String> findDescriptionSuggestions(@Param("categoryId") Long categoryId,
                                            @Param("prefix") String prefix);

    @Modifying
    @Query("UPDATE Transaction t SET t.card = NULL WHERE t.card.id = :cardId")
    int detachFromCard(@Param("cardId") Long cardId);

    /**
     * One-shot back-fill: pure-cash transactions (no linked card) created before the
     * "always set cashAmount = amount" rule have cashAmount = 0 in the DB. The new
     * cash-balance query uses cashAmount uniformly, so we set it to amount for those rows.
     * Idempotent — won't touch rows that already have a cashAmount or that have a card.
     */
    @Modifying
    @Query("UPDATE Transaction t SET t.cashAmount = t.amount " +
           "WHERE t.card IS NULL AND (t.cashAmount IS NULL OR t.cashAmount = 0) AND t.amount > 0")
    int backfillCardlessCashAmount();

    @Modifying
    @Query("UPDATE Transaction t SET t.category = NULL WHERE t.category.id = :categoryId")
    int detachFromCategory(@Param("categoryId") Long categoryId);

    Optional<Transaction> findFirstByTransferPairIdAndIdNot(Long transferPairId, Long excludeId);

    long countByInvestmentId(Long investmentId);

    /** Contribution history for one investment / savings goal — newest first. */
    List<Transaction> findByInvestmentIdOrderByTransactionDateDesc(Long investmentId);

    /** Whether anything at all was recorded on or before {@code date} — i.e. a month has data to close. */
    boolean existsByTransactionDateLessThanEqual(java.time.LocalDate date);
}
