package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.Card;

import java.math.BigDecimal;

@Repository
public interface CardRepository extends JpaRepository<Card, Long> {

    /**
     * Net balance change contributed by transactions linked to this card.
     * Subtracts the cash split before applying — a $100 expense with $30 cash only
     * decreases the card by $70. Legacy rows have null cashAmount and so default to 0.
     */
    @Query("""
            SELECT SUM(
                CASE WHEN t.type = 'INCOME' THEN 1 ELSE -1 END
                * (t.amount - COALESCE(t.cashAmount, 0))
            )
            FROM Transaction t
            WHERE t.card.id = :cardId AND t.currency = t.card.currency
            """)
    BigDecimal sumTransactionsByCardId(@Param("cardId") Long cardId);
}
