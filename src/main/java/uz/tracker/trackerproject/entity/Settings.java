package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Application-wide settings. Singleton row (id always == 1) until authentication
 * lands; once we have users, this becomes per-user with a unique FK.
 *
 * Holds the monthlyStableIncome that drives the tier dashboard and the FX rates
 * used to normalise mandatory/debt sums to UZS for tier comparison.
 */
@Entity
@Table(name = "settings")
@Getter @Setter @NoArgsConstructor
public class Settings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "monthly_stable_income", precision = 19, scale = 4)
    private BigDecimal monthlyStableIncome;

    @Enumerated(EnumType.STRING)
    @Column(name = "monthly_stable_income_currency")
    private Currency monthlyStableIncomeCurrency;

    /** 1 USD = ? UZS. Nullable; consumers fall back to a sentinel when missing. */
    @Column(name = "usd_to_uzs", precision = 19, scale = 4)
    private BigDecimal usdToUzs;

    /** 1 EUR = ? UZS. Nullable; consumers fall back to a sentinel when missing. */
    @Column(name = "eur_to_uzs", precision = 19, scale = 4)
    private BigDecimal eurToUzs;

    /**
     * Month (stored as its first day) from which the allocation ledger starts
     * accumulating recommended-vs-paid. Lets the user anchor the backlog so months
     * before they started tracking don't surface as "due". Null → OverviewService
     * defaults to the current month (no surprise backlog).
     */
    @Column(name = "allocation_tracking_start_month")
    private LocalDate allocationTrackingStartMonth;

    /**
     * Developer settings for the Telegram bot (a sibling Python service). The bot reads these
     * via the public {@code GET /api/v1/settings/telegram} endpoint at startup.
     *
     * telegramWebhookUrl  — public HTTPS URL Telegram pushes updates to (the bot registers it
     *                       with {@code setWebhook} on boot).
     * telegramWebViewUrl  — public HTTPS URL of the web app to open inside Telegram (surfaced as
     *                       the bot's "Open App" Web App button + chat menu button).
     */
    @Column(name = "telegram_webhook_url", length = 512)
    private String telegramWebhookUrl;

    @Column(name = "telegram_web_view_url", length = 512)
    private String telegramWebViewUrl;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = SINGLETON_ID;
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
