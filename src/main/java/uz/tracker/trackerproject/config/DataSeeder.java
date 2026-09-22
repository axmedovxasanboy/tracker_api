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
import java.util.LinkedHashMap;
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
        // First, and raw SQL, so it runs before any JPA read or write below. The Currency enum
        // holds UZS, USD and EUR (USD/EUR only as standalone cash pots — nothing converts), but a
        // database created while it held UZS alone still carries Hibernate's original CHECK
        // constraints, which reject the other two. `ddl-auto=update` never migrates them, so they
        // are rebuilt from today's enum, exactly as for sub_type below.
        rebuildCurrencyCheckConstraints();

        // Also before any JPA read — and before the CHECK constraint below is rebuilt from
        // the current enum, which would otherwise fail against the very rows it forbids.
        purgeRemovedExchangeRows();

        // Erase the retired card-number / PIN vault. Raw DDL, and the one migration here that
        // deliberately destroys data.
        dropCardSecrets();

        // Rebuild the sub_type CHECK constraint so it matches today's TransactionSubType
        // enum values. `ddl-auto=update` doesn't migrate Hibernate-generated CHECK
        // constraints when an enum gains or loses members —
        // the old constraint then rejects inserts of the new values at the DB level.
        rebuildSubTypeCheckConstraint();

        // Legacy STOCKS/CRYPTO investment types were removed — migrate old rows to OTHER first.
        migrateLegacyInvestmentTypes();

        // Freeze which allocation bucket each existing bucket-funding transaction credits, so
        // editing a holding can no longer re-bucket money that has already been spent — and, for
        // a closed month, already snapshotted.
        backfillAllocationBuckets();

        // Category kinds (and the place / From / To fields they added to the add form) are gone.
        // What those fields held survives in the description, which is now the only place any
        // of it is shown.
        foldRetiredCategoryExtras();

        // A transaction left without a description is named after its counterparty now, not its
        // category. Rows saved before that still carry the category name the old default wrote.
        nameTransactionsAfterCounterparty();

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
        // the Emergency category if it's missing.
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
        // The factory reset re-seeds through here, so the Uzbek names must be applied on this
        // path too — otherwise categories come back English-only until the next boot.
        backfillUzbekCategoryNames();
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
                cat("Salary",          CategoryType.INCOME,  "#10b981", "briefcase",   TransactionSubType.REGULAR_INCOME),
                cat("Freelance",       CategoryType.INCOME,  "#06b6d4", "laptop",      TransactionSubType.REGULAR_INCOME),
                cat("Loan Received",   CategoryType.INCOME,  "#f59e0b", "hand-coins",  TransactionSubType.LOAN_RECEIVED),
                cat("Loan Returned",   CategoryType.INCOME,  "#84cc16", "refresh-cw",  TransactionSubType.LOAN_RETURNED_TO_ME),
                cat("Investment Return", CategoryType.INCOME,"#8b5cf6", "trending-up", null),
                cat("Other Income",    CategoryType.INCOME,  "#6b7280", "plus-circle", null),
                // EXPENSE
                cat("Food & Dining",   CategoryType.EXPENSE, "#ef4444", "utensils",      TransactionSubType.REGULAR_EXPENSE),
                cat("Transport",       CategoryType.EXPENSE, "#f97316", "car",           TransactionSubType.REGULAR_EXPENSE),
                cat("Housing",         CategoryType.EXPENSE, "#eab308", "home",          TransactionSubType.REGULAR_EXPENSE),
                cat("Healthcare",      CategoryType.EXPENSE, "#ec4899", "heart",         TransactionSubType.REGULAR_EXPENSE),
                cat("Entertainment",   CategoryType.EXPENSE, "#a855f7", "music",         TransactionSubType.REGULAR_EXPENSE),
                cat("Shopping",        CategoryType.EXPENSE, "#14b8a6", "shopping-bag",  TransactionSubType.REGULAR_EXPENSE),
                cat("Education",       CategoryType.EXPENSE, "#3b82f6", "book",          TransactionSubType.REGULAR_EXPENSE),
                cat("Loan Given",      CategoryType.EXPENSE, "#f43f5e", "hand-coins",    TransactionSubType.LOAN_GIVEN),
                cat("Loan Repayment",  CategoryType.EXPENSE, "#f59e0b", "refresh-cw",    TransactionSubType.LOAN_REPAYMENT),
                cat("Bank Instalment", CategoryType.EXPENSE, "#6366f1", "building",      TransactionSubType.BANK_LOAN_PAYMENT),
                cat("Donation",        CategoryType.EXPENSE, "#d946ef", "heart-handshake", TransactionSubType.DONATION),
                cat("Investment",      CategoryType.EXPENSE, "#0ea5e9", "trending-up",   TransactionSubType.INVESTMENT),
                cat("Emergency Fund",  CategoryType.EXPENSE, "#f43f5e", "shield-alert",  TransactionSubType.EMERGENCY_CONTRIBUTION),
                cat("Everyday Spending", CategoryType.EXPENSE, "#94a3b8", "wallet",      TransactionSubType.EVERYDAY_SPENDING)
        );
    }

    /**
     * Ensure a category exists for the Emergency bucket so Overview pays of that
     * kinds auto-pick a category. Idempotent and safe on existing databases (where the default
     * seed above doesn't re-run). Only adds one when none already declares that sub-type.
     */
    private void ensureBucketCategories() {
        ensureCategoryForSubType("Emergency Fund", "#f43f5e", "shield-alert", TransactionSubType.EMERGENCY_CONTRIBUTION);
        ensureCategoryForSubType("Everyday Spending", "#94a3b8", "wallet",    TransactionSubType.EVERYDAY_SPENDING);
    }

    private void ensureCategoryForSubType(String name, String color, String icon, TransactionSubType subType) {
        if (!categoryRepository.findByApplicableSubType(subType).isEmpty()) return;
        categoryRepository.save(cat(name, CategoryType.EXPENSE, color, icon, subType));
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

    /**
     * Fill {@code transactions.allocation_bucket} for every row written before that column existed.
     *
     * <p>{@code ddl-auto=update} ADDS a column but never migrates data, so without this every
     * historical row would fall back to the old read-time derivation for ever, and one tick of a
     * holding's "savings goal" checkbox would still move money between the Investments and Savings
     * buckets of a month that is closed and snapshotted. The values written here are exactly what
     * that derivation returns today, so no figure on any screen changes — what changes is that
     * they stop being able to move.
     *
     * <p>Raw SQL because this is a one-shot pass over the whole table. Idempotent: the WHERE clause
     * matches only rows that have not been stamped yet, so later boots update nothing.
     *
     * <p>The two links to a holding are ORed rather than tried in the read path's order
     * (investmentId first, then originatingTransactionId) because by construction they resolve to
     * the same holding: a transaction carries an investmentId only when it tops one up, and only a
     * holding created FROM a transaction back-references it.
     */
    private void backfillAllocationBuckets() {
        try {
            int n = entityManager.createNativeQuery("""
                    UPDATE transactions t SET allocation_bucket = CASE t.sub_type
                        WHEN 'DONATION' THEN 'DONATION'
                        WHEN 'EMERGENCY_CONTRIBUTION' THEN 'EMERGENCY'
                        WHEN 'STOCK_PURCHASE' THEN 'STOCKS'
                        ELSE CASE WHEN EXISTS (
                                     SELECT 1 FROM investments i
                                      WHERE (i.id = t.investment_id OR i.originating_transaction_id = t.id)
                                        AND i.savings_goal = true)
                                  THEN 'SAVINGS' ELSE 'INVESTMENTS' END
                    END
                    WHERE t.allocation_bucket IS NULL
                      AND t.sub_type IN ('DONATION', 'EMERGENCY_CONTRIBUTION', 'STOCK_PURCHASE', 'INVESTMENT')
                    """).executeUpdate();
            if (n > 0) {
                System.out.println("[DataSeeder] Recorded the allocation bucket on " + n
                        + " existing transaction(s); those buckets can no longer shift when a holding is edited.");
            }
        } catch (Exception ignored) {
            // Tables not created yet on a virgin DB — there is nothing to back-fill.
        }
    }

    /**
     * One-shot, idempotent: move whatever the retired category extras held into the description.
     *
     * Two generations of data, two shapes. The current one composed a TRANSPORT route INTO the
     * description — "From >>> To", plus "\n&lt;note&gt;" when there was one — and the list parsed
     * it back out, showing the note as the title and the route beneath it. Nothing parses that
     * format any more, so it is rewritten as the line the list used to show: "note · From → To",
     * or just "From → To". Only TRANSPORT categories are touched, exactly as the parser only ever
     * read them, so a description that merely happens to contain " >>> " is left alone.
     *
     * The older generation kept `place` / `from_location` / `to_location` in their own columns.
     * Those are no longer mapped, so their contents are appended to the description unless it
     * already says the same thing — the old default description spelled them out when nothing
     * else was typed. The columns themselves are left intact rather than cleared: this reads
     * them, it never destroys them.
     *
     * Each half checks its columns exist before touching them. `run()` is a single transaction,
     * and on Postgres one failed statement aborts it for every statement after — a database
     * created after this change never gets these columns, so an unguarded query would take the
     * rest of the seeding down with it.
     */
    @SuppressWarnings("unchecked")
    private void foldRetiredCategoryExtras() {
        int routes = 0, columns = 0;
        if (columnExists("categories", "kind")) {
            List<Object[]> rows = entityManager.createNativeQuery("""
                    SELECT t.id, t.description FROM transactions t
                      JOIN categories c ON c.id = t.category_id
                     WHERE c.kind = 'TRANSPORT' AND t.description LIKE '% >>> %'
                    """).getResultList();
            for (Object[] row : rows) {
                String rewritten = unfoldRoute((String) row[1]);
                if (rewritten != null) routes += setDescription(((Number) row[0]).longValue(), rewritten);
            }
        }
        if (columnExists("transactions", "place") && columnExists("transactions", "from_location")
                && columnExists("transactions", "to_location")) {
            List<Object[]> rows = entityManager.createNativeQuery("""
                    SELECT id, description, place, from_location, to_location FROM transactions
                     WHERE coalesce(btrim(place), '') <> ''
                        OR coalesce(btrim(from_location), '') <> ''
                        OR coalesce(btrim(to_location), '') <> ''
                    """).getResultList();
            for (Object[] row : rows) {
                String description = row[1] == null ? "" : ((String) row[1]).trim();
                String place = blankToNull((String) row[2]);
                String from = blankToNull((String) row[3]);
                String to = blankToNull((String) row[4]);
                String extra = place != null ? place
                        : (from != null ? from : "—") + " → " + (to != null ? to : "—");
                // Contains, not equals: the old default wrote "Category @ place" and
                // "Category: from → to", which already carry it. It is also what makes a second
                // boot a no-op without having to clear the columns.
                if (description.toLowerCase().contains(extra.toLowerCase())) continue;
                columns += setDescription(((Number) row[0]).longValue(),
                        description.isEmpty() ? extra : description + " · " + extra);
            }
        }
        if (routes + columns > 0) {
            System.out.println("[DataSeeder] Folded the retired place / From / To fields into the description of "
                    + (routes + columns) + " transaction(s).");
        }
    }

    /** `"A >>> B\nnote"` → `"note · A → B"`; `"A >>> B"` → `"A → B"`. Null when it is not a route. */
    static String unfoldRoute(String description) {
        if (description == null) return null;
        int newline = description.indexOf('\n');
        String first = newline >= 0 ? description.substring(0, newline) : description;
        String note = newline >= 0 ? description.substring(newline + 1).trim() : "";
        int sep = first.indexOf(" >>> ");
        if (sep < 0) return null;
        String from = first.substring(0, sep).trim();
        String to = first.substring(sep + " >>> ".length()).trim();
        String route = (from.isEmpty() ? "—" : from) + " → " + (to.isEmpty() ? "—" : to);
        return note.isEmpty() ? route : note + " · " + route;
    }

    /**
     * One-shot, idempotent: give the rows the old default named after their category the name
     * the new default gives them — the person or holding they were with.
     *
     * Only a description that is still exactly what the app wrote on its own is replaced — the
     * category's name, "Parent — Category", or the bare "Transaction" fallback. Anything the user
     * typed is left as it is. The name comes from the record the transaction created or topped
     * up, and the generic names the backend falls back to when it has no name ("Borrower",
     * "Lender", "Investment", "Anonymous") are skipped, since they would only swap one
     * placeholder for another. An anonymous donation keeps its category name on purpose.
     */
    private void nameTransactionsAfterCounterparty() {
        record Source(String subType, String nameQuery) {}
        List<Source> sources = List.of(
                new Source("LOAN_GIVEN", """
                        SELECT lg.debtor_name FROM loans_given lg
                         WHERE (lg.id = t.loan_given_id OR lg.originating_transaction_id = t.id)
                           AND btrim(coalesce(lg.debtor_name, '')) NOT IN ('', 'Borrower')
                         ORDER BY (lg.id = t.loan_given_id) DESC LIMIT 1"""),
                new Source("LOAN_RECEIVED", """
                        SELECT lt.lender_name FROM loans_taken lt
                         WHERE lt.originating_transaction_id = t.id
                           AND btrim(coalesce(lt.lender_name, '')) NOT IN ('', 'Lender')
                         LIMIT 1"""),
                new Source("DONATION", """
                        SELECT d.recipient_name FROM donations d
                         WHERE d.originating_transaction_id = t.id
                           AND coalesce(d.anonymous, false) = false
                           AND btrim(coalesce(d.recipient_name, '')) NOT IN ('', 'Anonymous', 'Donation')
                         LIMIT 1"""),
                new Source("INVESTMENT", """
                        SELECT i.name FROM investments i
                         WHERE (i.id = t.investment_id OR i.originating_transaction_id = t.id)
                           AND btrim(coalesce(i.name, '')) NOT IN ('', 'Investment')
                         ORDER BY (i.id = t.investment_id) DESC LIMIT 1"""));
        int renamed = 0;
        for (Source source : sources) {
            try {
                renamed += entityManager.createNativeQuery("""
                        UPDATE transactions t SET description = (%s)
                         WHERE t.sub_type = :subType
                           AND (%s) IS NOT NULL
                           AND (t.description = 'Transaction' OR EXISTS (
                                SELECT 1 FROM categories c LEFT JOIN categories p ON p.id = c.parent_id
                                 WHERE c.id = t.category_id
                                   AND (t.description = c.name OR t.description = p.name || ' — ' || c.name)))
                        """.formatted(source.nameQuery(), source.nameQuery()))
                        .setParameter("subType", source.subType())
                        .executeUpdate();
            } catch (Exception ignored) {
                // Every column referenced is mapped, so the tables exist by the time this runs.
            }
        }
        if (renamed > 0) {
            System.out.println("[DataSeeder] Named " + renamed
                    + " transaction(s) after the borrower, lender, recipient or holding instead of their category.");
        }
    }

    /**
     * Drop the card vault's two columns — and with them the stored card numbers and PINs.
     *
     * <p>The app used to keep an AES-GCM ciphertext of the full PAN plus a hash of the card's PIN
     * so that a "reveal" screen could show the number back, rate-limited by
     * {@code RevealAttemptTracker}. The owner asked for it gone (2026-09-22): a personal tracker
     * has no use for a card number, and storing one is a risk with no return.
     *
     * <p>Unlike the other retired columns in this file — which are left in place because
     * {@code ddl-auto=update} never drops anything and an unmapped column is harmless — these are
     * DROPped, because dropping them IS the erasure. Idempotent: a no-op once they are gone.
     */
    private void dropCardSecrets() {
        int dropped = 0;
        for (String column : List.of("full_number", "pin")) {
            if (!columnExists("cards", column)) continue;
            entityManager.createNativeQuery("ALTER TABLE cards DROP COLUMN " + column).executeUpdate();
            dropped++;
        }
        if (dropped > 0) {
            System.out.println("[DataSeeder] Dropped the card vault (" + dropped
                    + " column(s)) — stored card numbers and PINs are erased.");
        }
    }

    private boolean columnExists(String table, String column) {
        Number n = (Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = current_schema() AND table_name = :table AND column_name = :column
                """).setParameter("table", table).setParameter("column", column).getSingleResult();
        return n.longValue() > 0;
    }

    private int setDescription(long transactionId, String description) {
        return entityManager.createNativeQuery("UPDATE transactions SET description = :d WHERE id = :id")
                .setParameter("d", description).setParameter("id", transactionId).executeUpdate();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Legacy data: the STOCKS and CRYPTO investment types were removed. Migrate any existing
     * rows to OTHER via native SQL — done BEFORE JPA reads them under the trimmed enum, which
     * would otherwise throw. Idempotent; non-fatal if the table doesn't exist yet.
     */
    private void migrateLegacyInvestmentTypes() {
        try {
            entityManager.createNativeQuery(
                    "UPDATE investments SET type = 'OTHER' WHERE type IN ('STOCKS','CRYPTO')").executeUpdate();
        } catch (Exception ignored) {
            // schema not yet created / non-Postgres — safe to skip.
        }
    }

    /**
     * Every currency column carries a Hibernate-generated CHECK constraint listing the enum
     * members that existed when the column was created. Adding USD/EUR does not update them,
     * so an insert of a new value fails at the DB level with a constraint violation. Rebuild
     * each one from today's enum. Idempotent, and safe on a virgin DB (the ALTER is skipped
     * if the table isn't there yet).
     */
    private void rebuildCurrencyCheckConstraints() {
        String inList = Arrays.stream(Currency.values())
                .map(v -> "'" + v.name() + "'")
                .collect(Collectors.joining(", "));
        // table -> currency column. Hibernate names the constraint <table>_<column>_check.
        Map<String, String> columns = new LinkedHashMap<>();
        for (String table : List.of("transactions", "cards", "cash_balances", "investments",
                "donations", "debts", "loans_taken", "loans_given", "monthly_payments",
                "emergencies", "bank_loans", "mark_paids", "month_close_wallets")) {
            columns.put(table, "currency");
        }
        columns.put("settings", "monthly_stable_income_currency");

        for (Map.Entry<String, String> e : columns.entrySet()) {
            String table = e.getKey();
            String column = e.getValue();
            String constraint = table + "_" + column + "_check";
            try {
                entityManager.createNativeQuery(
                        "ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint
                ).executeUpdate();
                entityManager.createNativeQuery(
                        "ALTER TABLE " + table + " ADD CONSTRAINT " + constraint +
                                " CHECK (" + column + " IS NULL OR " + column + " IN (" + inList + "))"
                ).executeUpdate();
            } catch (Exception ignored) {
                // Table not created yet, or a non-Postgres engine — the @Enumerated(STRING)
                // mapping still stops bogus values being written through JPA.
            }
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
        anon.setParent(donationRoot);
        anon.setAnonymizes(true);
        anon.setDescriptionRequired(false);
        categoryRepository.save(anon);
    }

    private Category cat(String name, CategoryType type, String color, String icon,
                         TransactionSubType subType) {
        Category c = new Category();
        c.setName(name);
        c.setType(type);
        c.setColor(color);
        c.setIcon(icon);
        c.setApplicableSubType(subType);
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
