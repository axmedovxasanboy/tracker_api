package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One holding in one month, written down as it happens (ANALYTICS-V2-SPEC §4.6): a holding's value
 * is overwritten in place, and a goal's target, monthly payment, deadline and plan/wish flag keep no
 * history, so a past month can never be rebuilt from the tables — only noted while it is current.
 * Written by {@code SnapshotScheduler} only (never by a read); read by Analytics' Goals for a goal
 * whose value cannot be rebuilt from its rows, and for the plan a past month asked for.
 * One row per holding and month. UZS.
 */
@Entity
@Table(name = "holding_month_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_holding_month_snapshot",
                columnNames = {"investment_id", "month"}))
@Getter @Setter @NoArgsConstructor
public class HoldingMonthSnapshot {

    /** DAILY — this month, rewritten every day. */
    public static final String DAILY = "DAILY";
    /** CLOSING — written on the 1st for the month just ended; nothing ever overwrites it. */
    public static final String CLOSING = "CLOSING";
    /** REBUILT — a catch-up worked out from the rows, for a month whose closing run was missed. */
    public static final String REBUILT = "REBUILT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The holding (Investment) — a plain id, so a deleted holding leaves its history behind. */
    @Column(name = "investment_id", nullable = false)
    private Long investmentId;

    /** The month, as its first day. */
    @Column(name = "month", nullable = false)
    private LocalDate month;

    /** The day the figures were taken on. */
    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    /** What the holding was worth: its current value, else what was put in. */
    @Column(name = "value", nullable = false, precision = 19, scale = 4)
    private BigDecimal value;

    /** Its {@code investedAmount}. */
    @Column(name = "invested", nullable = false, precision = 19, scale = 4)
    private BigDecimal invested;

    /**
     * The month's rows linked to the holding, up to {@link #asOf}: put in (any bucket, either link —
     * its id or the row that created it) and taken out (withdrawals). A reader compares them with the
     * same sums now, so a row back-dated into the month after the snapshot still counts in it.
     */
    @Column(name = "put_in_month", nullable = false, precision = 19, scale = 4)
    private BigDecimal putInMonth;

    @Column(name = "taken_out_month", nullable = false, precision = 19, scale = 4)
    private BigDecimal takenOutMonth;

    /** {@link #DAILY} · {@link #CLOSING} · {@link #REBUILT}. */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    /** INVESTMENT · EMERGENCY · GOAL — the Savings page's split. */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    // ── A goal's plan as it was (null / false for any other holding) ──

    @Column(name = "wish", nullable = false)
    private boolean wish;

    @Column(name = "target", precision = 19, scale = 4)
    private BigDecimal target;

    @Column(name = "monthly", precision = 19, scale = 4)
    private BigDecimal monthly;

    @Column(name = "deadline")
    private LocalDate deadline;

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
