package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-currency cash balance. Replaces the legacy "Cash wallet" Card. The displayed
 * current cash balance is computed as initialBalance + sum of cardless transactions
 * (income − expense) in the same currency.
 */
@Entity
@Table(name = "cash_balances", uniqueConstraints = @UniqueConstraint(columnNames = "currency"))
@Getter @Setter @NoArgsConstructor
public class CashBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private Currency currency;

    /** Manually-set starting point; deltas come from card_id IS NULL transactions. */
    @Column(name = "initial_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal initialBalance = BigDecimal.ZERO;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (initialBalance == null) initialBalance = BigDecimal.ZERO;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
