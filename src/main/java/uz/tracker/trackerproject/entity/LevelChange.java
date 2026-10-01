package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A start (UP) or an end (DOWN) of Level 5, from {@link #month} on (LEVELS-ALLOCATION-SPEC §1.1).
 * Written once, when the run of three ended months completes; re-checked only while its month is
 * in progress, so a correction made that month can still take it back — after that it stands.
 * Each client marks its own copy seen, so the web's dialog and the bot's message are independent.
 */
@Entity
@Table(name = "level_changes",
        uniqueConstraints = @UniqueConstraint(name = "uk_level_change_month", columnNames = "month"))
@Getter @Setter @NoArgsConstructor
public class LevelChange {
    // One change per month (uk_level_change_month): two requests evaluating the run at once (the web
    // loads /advisor and /levels/notice together) both see no row — the second insert fails, and
    // LevelService.refreshQuietly swallows it, so the dialog and the bot message appear once.

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The month it applies from, as its first day. */
    @Column(name = "month", nullable = false)
    private LocalDate month;

    /** The level from {@link #month} on: 5 for UP, the base level for DOWN. */
    @Column(nullable = false)
    private Integer level;

    /** The level before it: the base level for UP, 5 for DOWN. */
    @Column(name = "previous_level")
    private Integer previousLevel;

    /** UP | DOWN. A plain string: no CHECK constraint to migrate. */
    @Column(nullable = false, length = 8)
    private String kind;

    @Column(name = "seen_web_at")
    private LocalDateTime seenWebAt;

    @Column(name = "seen_bot_at")
    private LocalDateTime seenBotAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
