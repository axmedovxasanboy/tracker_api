package uz.tracker.trackerproject.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class TransactionSpecification {

    public static Specification<Transaction> withFilters(
            TransactionType type,
            Currency currency,
            Long categoryId,
            Long cardId,
            Long investmentId,
            LocalDate startDate,
            LocalDate endDate,
            String search,
            boolean excludeTransfers,
            boolean cashOnly
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (type != null)
                predicates.add(cb.equal(root.get("type"), type));
            if (currency != null)
                predicates.add(cb.equal(root.get("currency"), currency));
            if (categoryId != null)
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            if (cardId != null)
                predicates.add(cb.equal(root.get("card").get("id"), cardId));
            if (investmentId != null)
                predicates.add(cb.equal(root.get("investmentId"), investmentId));
            if (startDate != null)
                predicates.add(cb.greaterThanOrEqualTo(root.get("transactionDate"), startDate));
            if (endDate != null)
                predicates.add(cb.lessThanOrEqualTo(root.get("transactionDate"), endDate));
            if (search != null && !search.isBlank())
                predicates.add(cb.like(cb.lower(root.get("description")), "%" + search.toLowerCase() + "%"));
            if (excludeTransfers) {
                // Null sub-type is allowed (legacy rows / regular transactions); exclude
                // explicit TRANSFER_IN / TRANSFER_OUT / EXCHANGE_IN / EXCHANGE_OUT rows.
                predicates.add(cb.or(
                        cb.isNull(root.get("subType")),
                        root.get("subType").in(
                                TransactionSubType.TRANSFER_IN, TransactionSubType.TRANSFER_OUT,
                                TransactionSubType.EXCHANGE_IN, TransactionSubType.EXCHANGE_OUT).not()
                ));
            }
            if (cashOnly) {
                // "Cash" transactions are those that move the per-currency cash balance —
                // either pure-cash rows (card_id IS NULL) or split rows with a positive
                // cashAmount. Either way, cashAmount > 0 once the seeder back-fills.
                predicates.add(cb.greaterThan(root.get("cashAmount"), java.math.BigDecimal.ZERO));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
