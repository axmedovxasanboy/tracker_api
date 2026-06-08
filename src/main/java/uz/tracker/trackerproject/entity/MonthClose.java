package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A permanent, immutable record of a closed month in the monthly-envelope model. Once a
 * month is closed it cannot be reopened or edited; transactions dated in or before a closed
 * month are locked (see MonthCloseService.assertMonthOpen). All snapshot figures are stored
 * in UZS so the record is immune to later FX-rate changes.
 */
@Entity
@Table(name = "month_closes", uniqueConstraints = @UniqueConstraint(columnNames = "month"))
@Getter @Setter @NoArgsConstructor
public class MonthClose {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** First day of the closed month (a YYYY-MM pinned to day 1). Unique. */
    @Column(nullable = false, unique = true)
    private LocalDate month;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private LocalDateTime closedAt;

    // ── UZS snapshot of the month's envelope figures ──────────────────────────
    @Column(name = "start_balance_uzs", precision = 19, scale = 4)
    private BigDecimal startBalanceUzs;
    @Column(name = "income_uzs", precision = 19, scale = 4)
    private BigDecimal incomeUzs;
    @Column(name = "donation_uzs", precision = 19, scale = 4)
    private BigDecimal donationUzs;
    @Column(name = "emergency_uzs", precision = 19, scale = 4)
    private BigDecimal emergencyUzs;
    @Column(name = "investments_uzs", precision = 19, scale = 4)
    private BigDecimal investmentsUzs;
    @Column(name = "stocks_uzs", precision = 19, scale = 4)
    private BigDecimal stocksUzs;
    @Column(name = "savings_uzs", precision = 19, scale = 4)
    private BigDecimal savingsUzs;
    @Column(name = "everyday_spend_uzs", precision = 19, scale = 4)
    private BigDecimal everydaySpendUzs;
    @Column(name = "total_spent_uzs", precision = 19, scale = 4)
    private BigDecimal totalSpentUzs;
    @Column(name = "leftover_uzs", precision = 19, scale = 4)
    private BigDecimal leftoverUzs;

    @OneToMany(mappedBy = "monthClose", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MonthCloseWallet> wallets = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (closedAt == null) closedAt = LocalDateTime.now();
    }

    public void addWallet(MonthCloseWallet w) {
        w.setMonthClose(this);
        wallets.add(w);
    }
}
