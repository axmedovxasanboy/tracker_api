package uz.tracker.trackerproject.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public class TransactionSpecification {

    /**
     * The month {@code startDate}..{@code endDate} is exactly — the 1st to that same month's last
     * day — else null (a part of a month, more than one month, or an open end).
     */
    public static YearMonth wholeMonth(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.getDayOfMonth() != 1) return null;
        YearMonth month = YearMonth.from(startDate);
        return endDate.equals(month.atEndOfMonth()) ? month : null;
    }

    /**
     * @param accountingMonth null: the rows dated {@code startDate}..{@code endDate}, as always. A
     *                        month: the rows that count in it — the owner's rule "income counts in
     *                        the month it is FOR" — dated in it unless marked as another month's salary
     *                        ({@code salaryMonth}), plus the ones marked as its salary whatever day they
     *                        arrived; {@code startDate} / {@code endDate} are then not read (the caller
     *                        passes the month they span, {@link #wholeMonth}). The same condition
     *                        {@code TransactionRepository.sumBonusIncomeByCurrencyDateRange} uses.
     */
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
            boolean cashOnly,
            YearMonth accountingMonth
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
            if (accountingMonth != null) {
                // Only the salary's regular income carries a salaryMonth (TransactionService drops it
                // elsewhere), so every other row is still listed by the day it happened.
                LocalDate first = accountingMonth.atDay(1);
                predicates.add(cb.or(
                        cb.and(cb.isNull(root.get("salaryMonth")),
                                cb.between(root.<LocalDate>get("transactionDate"), first, accountingMonth.atEndOfMonth())),
                        cb.equal(root.get("salaryMonth"), first)));
            } else {
                if (startDate != null)
                    predicates.add(cb.greaterThanOrEqualTo(root.get("transactionDate"), startDate));
                if (endDate != null)
                    predicates.add(cb.lessThanOrEqualTo(root.get("transactionDate"), endDate));
            }
            if (search != null && !search.isBlank())
                predicates.add(cb.like(cb.lower(root.get("description")), "%" + search.toLowerCase() + "%"));
            if (excludeTransfers) {
                // Null sub-type is allowed (legacy rows / regular transactions); exclude
                // explicit TRANSFER_IN / TRANSFER_OUT rows.
                predicates.add(cb.or(
                        cb.isNull(root.get("subType")),
                        root.get("subType").in(
                                TransactionSubType.TRANSFER_IN, TransactionSubType.TRANSFER_OUT).not()
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
