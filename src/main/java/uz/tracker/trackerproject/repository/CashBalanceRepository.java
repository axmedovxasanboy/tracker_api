package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.CashBalance;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface CashBalanceRepository extends JpaRepository<CashBalance, Long> {

    Optional<CashBalance> findByCurrency(Currency currency);

    /**
     * Net cash delta in a given currency. Sums the CASH PORTION of every transaction:
     *   – pure-cash rows (card_id IS NULL) contribute t.amount
     *   – split rows (card_id NOT NULL, cashAmount > 0) contribute their t.cashAmount
     *   – pure-card rows (cashAmount = 0) contribute 0
     *
     * Transfers stay excluded — they always have a card on BOTH sides, so they never
     * touch the cash balance even without the filter (defensive only).
     *
     * Exchanges are NOT excluded: a cash↔card exchange has a cash-side row with
     * card_id NULL that legitimately moves real money in/out of cash. Pure card↔card
     * exchanges contribute 0 anyway via the CASE above. The dashboard aggregates
     * still exclude exchanges separately so they don't inflate income/expense totals.
     *
     * NOTE: pure-cash rows created before the "always set cashAmount" rule may have
     * cashAmount = 0 even though they SHOULD count their amount. DataSeeder back-fills
     * those on startup; COALESCE(...) is just defence-in-depth.
     */
    @Query("""
            SELECT COALESCE(SUM(
                CASE WHEN t.type = uz.tracker.trackerproject.enums.TransactionType.INCOME
                     THEN  CASE WHEN t.card IS NULL THEN t.amount ELSE COALESCE(t.cashAmount, 0) END
                     ELSE -CASE WHEN t.card IS NULL THEN t.amount ELSE COALESCE(t.cashAmount, 0) END
                END
            ), 0)
            FROM Transaction t
            WHERE t.currency = :currency
              AND (t.subType IS NULL OR t.subType NOT IN (
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_IN,
                    uz.tracker.trackerproject.enums.TransactionSubType.TRANSFER_OUT))
            """)
    BigDecimal sumCashlessTransactions(@Param("currency") Currency currency);
}
