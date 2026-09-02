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
import java.util.Map;
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
        // MUST be first: the app is UZS-only now, and Hibernate throws
        // "No enum constant Currency.USD" the moment it reads a legacy non-UZS row.
        // This is raw SQL precisely so it runs before any JPA read below.
        purgeNonUzsData();

        // Also before any JPA read — and before the CHECK constraint below is rebuilt from
        // the current enum, which would otherwise fail against the very rows it forbids.
        purgeRemovedExchangeRows();

        // Rebuild the sub_type CHECK constraint so it matches today's TransactionSubType
        // enum values. `ddl-auto=update` doesn't migrate Hibernate-generated CHECK
        // constraints when an enum gains or loses members —
        // the old constraint then rejects inserts of the new values at the DB level.
        rebuildSubTypeCheckConstraint();

        // Legacy STOCKS/CRYPTO investment types were removed — migrate old rows to OTHER first.
        migrateLegacyInvestmentTypes();

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

        if (categoryRepository.count() == 0) {
            categoryRepository.saveAll(defaultCategories());
        }
        // Idempotent — runs AFTER the default-seed check above so a fresh DB still seeds the full
        // set (adding these early would make count() > 0 and skip it). On an existing DB it adds
        // the Stocks + Emergency categories if they're missing.
        ensureBucketCategories();

        // Runs last: every default and bucket category now exists, so all of them get
        // their Uzbek name in one pass.
        backfillUzbekCategoryNames();
    }

    /**
     * Seed a clean database with the default category set (+ the Anonymous donation
     * sub-category) and the sub_type CHECK constraint. Called on first boot via
     * {@link #run} and again by the factory reset (ResetService) right after every
     * table has been truncated, so a fresh signup lands on a usable app. Idempotent:
     * the category seeding is a no-op when any category already exists.
     */
    @Transactional
    public void seedDefaults() {
        rebuildSubTypeCheckConstraint();
        if (categoryRepository.count() == 0) {
            categoryRepository.saveAll(defaultCategories());
        }
        ensureDonationAnonymous();
        ensureBucketCategories();
    }

    /**
     * Uzbek names for the seeded defaults. Keyed by the canonical ENGLISH name, which
     * stays the identifier — only the display translation is added. Owner-editable
     * afterwards from the Categories page.
     */
    private static final Map<String, String> DEFAULT_UZ_NAMES = Map.ofEntries(
            Map.entry("Salary", "Oylik maosh"),
            Map.entry("Freelance", "Frilans"),
            Map.entry("Loan Received", "Olingan qarz"),
            Map.entry("Loan Returned", "Qaytarilgan qarz"),
            Map.entry("Investment Return", "Investitsiya daromadi"),
            Map.entry("Other Income", "Boshqa daromad"),
            Map.entry("Food & Dining", "Ovqat va ichimlik"),
            Map.entry("Transport", "Transport"),
            Map.entry("Housing", "Uy-joy"),
            Map.entry("Healthcare", "Sog'liqni saqlash"),
            Map.entry("Entertainment", "Ko'ngilochar"),
            Map.entry("Shopping", "Xaridlar"),
            Map.entry("Education", "Ta'lim"),
            Map.entry("Loan Given", "Berilgan qarz"),
            Map.entry("Loan Repayment", "Qarzni to'lash"),
            Map.entry("Bank Instalment", "Bank to'lovi"),
            Map.entry("Donation", "Xayriya"),
            Map.entry("Investment", "Investitsiya"),
            Map.entry("Stocks", "Aksiyalar"),
            Map.entry("Emergency Fund", "Favqulodda jamg'arma"),
            Map.entry("Everyday Spending", "Kundalik xarajat"),
            Map.entry("Anonymous", "Anonim"));

    /**
     * Give every category an Uzbek name where we know one and the row doesn't have one yet.
     * Idempotent: only fills nulls, so an owner edit is never overwritten.
     */
    private void backfillUzbekCategoryNames() {
        List<Category> all = categoryRepository.findAll();
        int filled = 0;
        for (Category c : all) {
            if (c.getNameUz() != null && !c.getNameUz().isBlank()) continue;
            String uz = DEFAULT_UZ_NAMES.get(c.getName());
            if (uz == null) continue;
            c.setNameUz(uz);
            categoryRepository.save(c);
            filled++;
        }
        if (filled > 0) {
            System.out.println("[DataSeeder] Added Uzbek names to " + filled + " category/categories.");
        }
    }

    private List<Category> defaultCategories() {
        return List.of(
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
                cat("Investment",      CategoryType.EXPENSE, "#0ea5e9", "trending-up",   TransactionSubType.INVESTMENT,        CategoryKind.GENERIC),
                cat("Stocks",          CategoryType.EXPENSE, "#0d9488", "line-chart",    TransactionSubType.STOCK_PURCHASE,    CategoryKind.GENERIC),
                cat("Emergency Fund",  CategoryType.EXPENSE, "#f43f5e", "shield-alert",  TransactionSubType.EMERGENCY_CONTRIBUTION, CategoryKind.GENERIC),
                cat("Everyday Spending", CategoryType.EXPENSE, "#94a3b8", "wallet",      TransactionSubType.EVERYDAY_SPENDING, CategoryKind.GENERIC)
        );
    }

    /**
     * Ensure a category exists for the Stocks and Emergency buckets so Overview pays of those
     * kinds auto-pick a category. Idempotent and safe on existing databases (where the default
     * seed above doesn't re-run). Only adds one when none already declares that sub-type.
     */
    private void ensureBucketCategories() {
        ensureCategoryForSubType("Stocks",         "#0d9488", "line-chart",   TransactionSubType.STOCK_PURCHASE);
        ensureCategoryForSubType("Emergency Fund", "#f43f5e", "shield-alert", TransactionSubType.EMERGENCY_CONTRIBUTION);
        ensureCategoryForSubType("Everyday Spending", "#94a3b8", "wallet",    TransactionSubType.EVERYDAY_SPENDING);
    }

    private void ensureCategoryForSubType(String name, String color, String icon, TransactionSubType subType) {
        if (!categoryRepository.findByApplicableSubType(subType).isEmpty()) return;
        categoryRepository.save(cat(name, CategoryType.EXPENSE, color, icon, subType, CategoryKind.GENERIC));
    }

    /**
     * Legacy data: the STOCKS and CRYPTO investment types were removed. Migrate any existing
     * rows to OTHER via native SQL — done BEFORE JPA reads them under the trimmed enum, which
     * would otherwise throw. Idempotent; non-fatal if the table doesn't exist yet.
     */
    /**
     * One-way migration to the UZS-only model: delete every row denominated in a currency
     * that no longer exists. Runs before anything else in {@link #run} because Hibernate
     * cannot even read a row whose currency is not a {@link Currency} constant.
     *
     * Children go before parents so foreign keys stay satisfied, and transactions attached
     * to a doomed wallet/record are removed even if their own currency column looks fine.
     * Idempotent: once the data is clean every statement matches zero rows, so this is a
     * no-op on every later boot.
     */
    private void purgeNonUzsData() {
        // Children first: anything pointing at a wallet or record that is about to go.
        List<String> statements = List.of(
                "DELETE FROM transactions WHERE card_id IN (SELECT id FROM cards WHERE currency <> 'UZS')",
                "DELETE FROM transactions WHERE investment_id IN (SELECT id FROM investments WHERE currency <> 'UZS')",
                "DELETE FROM transactions WHERE monthly_payment_id IN "
                        + "(SELECT id FROM monthly_payments WHERE currency <> 'UZS')",
                "DELETE FROM transactions WHERE repaid_loan_taken_id IN "
                        + "(SELECT id FROM loans_taken WHERE currency <> 'UZS')",
                "DELETE FROM transactions WHERE repaid_loan_given_id IN "
                        + "(SELECT id FROM loans_given WHERE currency <> 'UZS')",
                "DELETE FROM transactions WHERE repaid_debt_id IN (SELECT id FROM debts WHERE currency <> 'UZS')",
                "DELETE FROM transactions WHERE currency <> 'UZS'",
                "DELETE FROM month_close_wallets WHERE currency <> 'UZS' "
                        + "OR card_id IN (SELECT id FROM cards WHERE currency <> 'UZS')",
                "DELETE FROM mark_paids WHERE currency <> 'UZS'",
                // Then the records themselves.
                "DELETE FROM cards WHERE currency <> 'UZS'",
                "DELETE FROM cash_balances WHERE currency <> 'UZS'",
                "DELETE FROM investments WHERE currency <> 'UZS'",
                "DELETE FROM donations WHERE currency <> 'UZS'",
                "DELETE FROM debts WHERE currency <> 'UZS'",
                "DELETE FROM loans_taken WHERE currency <> 'UZS'",
                "DELETE FROM loans_given WHERE currency <> 'UZS'",
                "DELETE FROM monthly_payments WHERE currency <> 'UZS'",
                "DELETE FROM emergencies WHERE currency <> 'UZS'",
                "DELETE FROM bank_loans WHERE currency <> 'UZS'",
                // The stable-income currency is a setting, not a row — just restamp it.
                "UPDATE settings SET monthly_stable_income_currency = 'UZS' "
                        + "WHERE monthly_stable_income_currency IS NOT NULL "
                        + "AND monthly_stable_income_currency <> 'UZS'");

        int purged = 0;
        for (String sql : statements) {
            try {
                purged += entityManager.createNativeQuery(sql).executeUpdate();
            } catch (Exception ignored) {
                // Table not created yet on a virgin DB, or a non-Postgres engine — safe to skip.
            }
        }
        if (purged > 0) {
            System.out.println("[DataSeeder] UZS-only migration: removed " + purged
                    + " row(s) denominated in a currency that is no longer supported.");
        }
    }

    /**
     * The wallet-to-wallet "exchange" feature was removed, along with its two sub-types.
     * Any surviving row would be unreadable (`No enum constant …EXCHANGE_IN`) and would also
     * break the rebuilt sub_type CHECK constraint, so the rows go.
     *
     * NOTE: an exchange was a PAIR of rows that moved money between two wallets, so deleting
     * them restores the source wallet and debits the destination — wallet balances shift by
     * design (the owner accepted this when the feature was dropped).
     * Idempotent: a no-op once nothing is left.
     */
    private void purgeRemovedExchangeRows() {
        try {
            int n = entityManager.createNativeQuery(
                    "DELETE FROM transactions WHERE sub_type IN ('EXCHANGE_IN','EXCHANGE_OUT')")
                    .executeUpdate();
            if (n > 0) {
                System.out.println("[DataSeeder] Removed " + n
                        + " transaction(s) from the deleted wallet-exchange feature; "
                        + "affected wallet balances have shifted accordingly.");
            }
        } catch (Exception ignored) {
            // Table not created yet on a virgin DB — safe to skip.
        }
    }

    private void migrateLegacyInvestmentTypes() {
        try {
            entityManager.createNativeQuery(
                    "UPDATE investments SET type = 'OTHER' WHERE type IN ('STOCKS','CRYPTO')").executeUpdate();
        } catch (Exception ignored) {
            // schema not yet created / non-Postgres — safe to skip.
        }
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
