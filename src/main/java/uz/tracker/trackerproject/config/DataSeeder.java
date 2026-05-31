package uz.tracker.trackerproject.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.entity.CashBalance;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.enums.CardType;
import uz.tracker.trackerproject.enums.CategoryKind;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CashBalanceRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final CategoryRepository categoryRepository;
    private final CardRepository cardRepository;
    private final CashBalanceRepository cashBalanceRepository;
    private final TransactionRepository transactionRepository;
    private final LoanTakenRepository loanTakenRepository;
    private final DebtRepository debtRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void run(String... args) {
        // Rebuild the sub_type CHECK constraint so it matches today's TransactionSubType
        // enum values. `ddl-auto=update` doesn't migrate Hibernate-generated CHECK
        // constraints when an enum gains new members (e.g. EXCHANGE_IN/EXCHANGE_OUT) —
        // the old constraint then rejects inserts of the new values at the DB level.
        rebuildSubTypeCheckConstraint();

        // Back-fill kind on any pre-existing categories so the new column has a value everywhere.
        categoryRepository.findAll().forEach(c -> {
            if (c.getKind() == null) {
                c.setKind(inferKind(c.getName()));
                categoryRepository.save(c);
            }
        });

        // Ensure an "Anonymous" sub-category exists under Donation. Seeded for everyone,
        // regardless of whether they already have other categories.
        ensureDonationAnonymous();

        // One-shot migration: any legacy CASH-type cards become CashBalance rows.
        migrateCashCardsToCashBalance();

        // One-shot back-fill: cardless rows must have cashAmount = amount under the
        // new cash-balance scheme (see CashBalanceRepository.sumCashlessTransactions).
        transactionRepository.backfillCardlessCashAmount();

        // One-shot back-fill: LoanTaken/Debt monthlyPayment is frozen at creation now,
        // but pre-existing rows have null. Compute once from current (remaining,
        // dueDate) so the tier dashboard has a stable monthly value for them too.
        // Idempotent — only touches rows where monthlyPayment is null.
        backfillLoanMonthlyPayment();

        if (categoryRepository.count() > 0) return;

        List<Category> defaults = List.of(
                // INCOME
                cat("Salary",          CategoryType.INCOME,  "#10b981", "briefcase",   TransactionSubType.REGULAR_INCOME,      CategoryKind.GENERIC),
                cat("Freelance",       CategoryType.INCOME,  "#06b6d4", "laptop",      TransactionSubType.REGULAR_INCOME,      CategoryKind.GENERIC),
                cat("Loan Received",   CategoryType.INCOME,  "#f59e0b", "hand-coins",  TransactionSubType.LOAN_RECEIVED,       CategoryKind.GENERIC),
                cat("Loan Returned",   CategoryType.INCOME,  "#84cc16", "refresh-cw",  TransactionSubType.LOAN_RETURNED_TO_ME, CategoryKind.GENERIC),
                cat("Investment Return", CategoryType.INCOME,"#8b5cf6", "trending-up", null,                                   CategoryKind.GENERIC),
                cat("Other Income",    CategoryType.INCOME,  "#6b7280", "plus-circle", null,                                   CategoryKind.GENERIC),
                // EXPENSE
                cat("Food & Dining",   CategoryType.EXPENSE, "#ef4444", "utensils",      TransactionSubType.REGULAR_EXPENSE,   CategoryKind.FOOD),
                cat("Transport",       CategoryType.EXPENSE, "#f97316", "car",           TransactionSubType.REGULAR_EXPENSE,   CategoryKind.TRANSPORT),
                cat("Housing",         CategoryType.EXPENSE, "#eab308", "home",          TransactionSubType.REGULAR_EXPENSE,   CategoryKind.GENERIC),
                cat("Healthcare",      CategoryType.EXPENSE, "#ec4899", "heart",         TransactionSubType.REGULAR_EXPENSE,   CategoryKind.GENERIC),
                cat("Entertainment",   CategoryType.EXPENSE, "#a855f7", "music",         TransactionSubType.REGULAR_EXPENSE,   CategoryKind.GENERIC),
                cat("Shopping",        CategoryType.EXPENSE, "#14b8a6", "shopping-bag",  TransactionSubType.REGULAR_EXPENSE,   CategoryKind.GENERIC),
                cat("Education",       CategoryType.EXPENSE, "#3b82f6", "book",          TransactionSubType.REGULAR_EXPENSE,   CategoryKind.GENERIC),
                cat("Loan Given",      CategoryType.EXPENSE, "#f43f5e", "hand-coins",    TransactionSubType.LOAN_GIVEN,        CategoryKind.GENERIC),
                cat("Loan Repayment",  CategoryType.EXPENSE, "#f59e0b", "refresh-cw",    TransactionSubType.LOAN_REPAYMENT,    CategoryKind.GENERIC),
                cat("Bank Instalment", CategoryType.EXPENSE, "#6366f1", "building",      TransactionSubType.BANK_LOAN_PAYMENT, CategoryKind.GENERIC),
                cat("Donation",        CategoryType.EXPENSE, "#d946ef", "heart-handshake", TransactionSubType.DONATION,        CategoryKind.GENERIC),
                cat("Investment",      CategoryType.EXPENSE, "#0ea5e9", "trending-up",   TransactionSubType.INVESTMENT,        CategoryKind.GENERIC)
        );
        categoryRepository.saveAll(defaults);
    }

    /**
     * Drop and recreate the CHECK constraint on transactions.sub_type so it includes
     * every current TransactionSubType enum value. Hibernate's `ddl-auto=update` will
     * happily ADD a constraint when none exists, but it does not REPLACE an existing
     * one when the enum gains new members — leaving the DB to reject the new values.
     *
     * We do this defensively (catch + swallow) so it can't take the app down if the
     * underlying DB engine isn't Postgres or has a different constraint name scheme.
     */
    private void rebuildSubTypeCheckConstraint() {
        String inList = Arrays.stream(TransactionSubType.values())
                .map(v -> "'" + v.name() + "'")
                .collect(Collectors.joining(", "));
        try {
            entityManager.createNativeQuery(
                    "ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_sub_type_check"
            ).executeUpdate();
            entityManager.createNativeQuery(
                    "ALTER TABLE transactions ADD CONSTRAINT transactions_sub_type_check " +
                            "CHECK (sub_type IS NULL OR sub_type IN (" + inList + "))"
            ).executeUpdate();
        } catch (Exception ignored) {
            // Idempotent — non-fatal if the constraint name differs or the DB doesn't
            // support this form. The @Enumerated(EnumType.STRING) mapping still
            // prevents bogus values from being written through JPA.
        }
    }

    /**
     * Migrate any legacy CASH-type cards into the new CashBalance entity:
     *   – per currency, sum the cards' initial balances into a CashBalance row
     *   – detach every transaction linked to those cards (card_id → NULL)
     *   – delete the cards.
     * Safe to re-run: only acts on remaining CASH cards.
     */
    private void migrateCashCardsToCashBalance() {
        List<Card> cashCards = cardRepository.findAll().stream()
                .filter(c -> c.getType() == CardType.CASH)
                .toList();
        if (cashCards.isEmpty()) return;

        for (Card c : cashCards) {
            Currency currency = c.getCurrency();
            CashBalance balance = cashBalanceRepository.findByCurrency(currency)
                    .orElseGet(() -> {
                        CashBalance fresh = new CashBalance();
                        fresh.setCurrency(currency);
                        fresh.setInitialBalance(BigDecimal.ZERO);
                        return fresh;
                    });
            BigDecimal extra = c.getInitialBalance() != null ? c.getInitialBalance() : BigDecimal.ZERO;
            balance.setInitialBalance(balance.getInitialBalance().add(extra));
            cashBalanceRepository.save(balance);

            transactionRepository.detachFromCard(c.getId());
            cardRepository.delete(c);
        }
    }

    private void ensureDonationAnonymous() {
        Category donationRoot = categoryRepository.findByParentIsNull().stream()
                .filter(c -> "Donation".equalsIgnoreCase(c.getName()))
                .findFirst()
                .orElse(null);
        if (donationRoot == null) return;
        boolean exists = categoryRepository.findByParentId(donationRoot.getId()).stream()
                .anyMatch(c -> Boolean.TRUE.equals(c.getAnonymizes())
                        || "Anonymous".equalsIgnoreCase(c.getName()));
        if (exists) return;
        Category anon = new Category();
        anon.setName("Anonymous");
        anon.setType(donationRoot.getType());
        anon.setColor("#64748b");
        anon.setIcon("user-x");
        anon.setApplicableSubType(donationRoot.getApplicableSubType());
        anon.setKind(donationRoot.getKind());
        anon.setParent(donationRoot);
        anon.setAnonymizes(true);
        anon.setDescriptionRequired(false);
        categoryRepository.save(anon);
    }

    /** Best-effort inference for legacy rows that pre-date the kind column. */
    private CategoryKind inferKind(String name) {
        if (name == null) return CategoryKind.GENERIC;
        String n = name.toLowerCase();
        if (n.contains("food") || n.contains("dining") || n.contains("restaurant") || n.contains("cafe")) return CategoryKind.FOOD;
        if (n.contains("transport") || n.contains("taxi") || n.contains("metro") || n.contains("bus") || n.contains("travel")) return CategoryKind.TRANSPORT;
        return CategoryKind.GENERIC;
    }

    private Category cat(String name, CategoryType type, String color, String icon,
                         TransactionSubType subType, CategoryKind kind) {
        Category c = new Category();
        c.setName(name);
        c.setType(type);
        c.setColor(color);
        c.setIcon(icon);
        c.setApplicableSubType(subType);
        c.setKind(kind);
        return c;
    }

    private void backfillLoanMonthlyPayment() {
        loanTakenRepository.findAll().forEach(l -> {
            if (l.getMonthlyPayment() != null) return;
            BigDecimal monthly = freezeMonthly(l.getTotalAmount(), l.getPaidAmount(), l.getDueDate());
            if (monthly == null) return;
            l.setMonthlyPayment(monthly);
            loanTakenRepository.save(l);
        });
        debtRepository.findAll().forEach(d -> {
            if (d.getMonthlyPayment() != null) return;
            BigDecimal monthly = freezeMonthly(d.getTotalAmount(), d.getPaidAmount(), d.getDueDate());
            if (monthly == null) return;
            d.setMonthlyPayment(monthly);
            debtRepository.save(d);
        });
    }

    /**
     * Same formula as FinanceService.deriveMonthlyContribution — duplicated here to keep
     * the seeder self-contained. Returns null when there's nothing to freeze (already
     * paid off), so the back-fill can skip the row without touching it.
     */
    private BigDecimal freezeMonthly(BigDecimal totalAmount, BigDecimal paidAmount, java.time.LocalDate dueDate) {
        BigDecimal remaining = (totalAmount == null ? BigDecimal.ZERO : totalAmount)
                .subtract(paidAmount == null ? BigDecimal.ZERO : paidAmount);
        if (remaining.signum() <= 0) return null;
        long months = 1L;
        if (dueDate != null) {
            long between = java.time.temporal.ChronoUnit.MONTHS.between(java.time.LocalDate.now(), dueDate);
            months = Math.max(1L, between);
        }
        return remaining.divide(BigDecimal.valueOf(months), java.math.MathContext.DECIMAL64);
    }
}
