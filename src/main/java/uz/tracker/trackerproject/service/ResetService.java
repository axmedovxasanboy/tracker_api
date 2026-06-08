package uz.tracker.trackerproject.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.config.DataSeeder;
import uz.tracker.trackerproject.entity.AppUser;
import uz.tracker.trackerproject.exception.UnauthorizedException;
import uz.tracker.trackerproject.repository.AppUserRepository;

/**
 * Factory reset — the "Danger Zone" action. Re-verifies the current account password,
 * then wipes EVERY table (including the account and settings) and re-seeds the default
 * categories, leaving the app exactly as it would be on a brand-new boot: the first
 * request after this returns {@code needsSignup = true}, so the user signs up afresh.
 *
 * <p>Irreversible. There is no soft-delete and no backup — the rows are gone.
 */
@Service
@RequiredArgsConstructor
public class ResetService {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final DataSeeder dataSeeder;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Every table, listed so the truncate is explicit (no reliance on metadata scans).
     * {@code RESTART IDENTITY} resets the id sequences so a fresh start really starts at 1;
     * {@code CASCADE} satisfies the FKs between them regardless of order. Postgres-specific,
     * which matches the project's single supported engine.
     */
    private static final String TRUNCATE_ALL =
            "TRUNCATE TABLE " +
                    "transactions, monthly_payments, loans_taken, loans_given, debts, " +
                    "donations, investments, emergencies, bank_loans, categories, cards, " +
                    "cash_balances, level_configs, level_allocation_rules, " +
                    "month_close_wallets, month_closes, mark_paids, settings, app_users " +
                    "RESTART IDENTITY CASCADE";

    @Transactional
    public void reset(String username, String rawPassword) {
        AppUser user = users.findByUsername(username == null ? "" : username).orElse(null);
        String raw = rawPassword == null ? "" : rawPassword;
        if (user == null || !encoder.matches(raw, user.getPasswordHash())) {
            throw new UnauthorizedException("Incorrect password.");
        }

        // Drop everything, then drop the (now-stale) persistence context so the re-seed
        // below works against a clean slate rather than cached managed entities.
        entityManager.createNativeQuery(TRUNCATE_ALL).executeUpdate();
        entityManager.clear();

        // Re-seed defaults so the freshly signed-up account has a usable category set.
        dataSeeder.seedDefaults();
    }
}
