package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One version of a level's savings rules (LEVELS-ALLOCATION-SPEC §1.2–1.3): from {@link #fromMonth}
 * until that level's next version, every month on the level asks these percentages — seven
 * situations × three buckets — and splits "or more left" from "under" at {@link #cutoff}. Months
 * before a level's first version use the first one.
 */
@Entity
@Table(name = "level_rule_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_level_rule_version", columnNames = {"level", "from_month"}))
@Getter @Setter @NoArgsConstructor
public class LevelRuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 1..5. */
    @Column(nullable = false)
    private Integer level;

    /** The first month it applies to, as its first day. */
    @Column(name = "from_month", nullable = false)
    private LocalDate fromMonth;

    /** "How much is left after bills and loans" that splits the "or more left" rows from the "under" rows. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal cutoff;

    /** Situation key (NO_DEBT, BANK_LOAN_TIGHT, …) → its three percentages. Plain strings, not an enum: no CHECK constraint to migrate. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "level_rule_situations", joinColumns = @JoinColumn(name = "version_id"))
    @MapKeyColumn(name = "situation", length = 32)
    private Map<String, SituationPercents> rules = new LinkedHashMap<>();

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
