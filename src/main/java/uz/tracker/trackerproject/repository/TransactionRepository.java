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
     * Sum of amount by type+currency, EXCLUDING transfer + exchange halves. Both kinds
     * just move money between wallets and shouldn't inflate dashboard income/expense totals.
     */
    @Query("""
            SELECT SUM(t.amount)
            FROM Transaction t
            WHERE t.type = :type AND t.currency = :currency
              AND (t.subType IS NULL OR t.subType NOT IN (
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_IN,
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_OUT,
                    uz.tracker.trackerproject.enums.TransactionSubType.EXCHANGE_IN,
                    uz.tracker.trackerproject.enums.TransactionSubType.EXCHANGE_OUT))
            """)
    BigDecimal sumByTypeAndCurrency(@Param("type") TransactionType type,
                                    @Param("currency") Currency currency);

    @Query("""
            SELECT COUNT(t) FROM Transaction t
            WHERE t.currency = :currency
              AND (t.subType IS NULL OR t.subType NOT IN (
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_IN,
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_OUT,
                    uz.tracker.trackerproject.enums.TransactionSubType.EXCHANGE_IN,
                    uz.tracker.trackerproject.enums.TransactionSubType.EXCHANGE_OUT))
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
                    uz.tracker.trackerproject.enums.TransactionSubType.EXCHANGE_IN,
                    uz.tracker.trackerproject.enums.TransactionSubType.EXCHANGE_OUT,
                    uz.tracker.trackerproject.enums.TransactionSubType.EVERYDAY_SPENDING))
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
            """)
    BigDecimal sumBonusIncomeByCurrencyDateRange(
            @Param("currency") Currency currency,
            @Param("start") java.time.LocalDate start,
            @Param("end") java.time.LocalDate end);

    /** Repayment lookup helpers — newest payment first for the per-loan history view. */
    List<Transaction> findByRepaidLoanTakenIdOrderByTransactionDateDesc(Long loanTakenId);
    List<Transaction> findByRepaidLoanGivenIdOrderByTransactionDateDesc(Long loanGivenId);
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

    /** Distinct non-null places, optionally scoped to a category root and filtered by prefix. */
    @Query(value = """
            SELECT DISTINCT t.place
            FROM transactions t
            LEFT JOIN categories c ON c.id = t.category_id
            WHERE t.place IS NOT NULL AND t.place <> ''
              AND (CAST(:categoryId AS bigint) IS NULL OR t.category_id = :categoryId OR c.parent_id = :categoryId)
              AND (:prefix IS NULL OR :prefix = '' OR t.place ILIKE '%' || :prefix || '%')
            ORDER BY t.place
            LIMIT 15
            """, nativeQuery = true)
    List<String> findDistinctPlaces(@Param("categoryId") Long categoryId, @Param("prefix") String prefix);

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
}
