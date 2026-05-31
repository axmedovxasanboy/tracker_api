package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "monthly_payments")
@Getter @Setter @NoArgsConstructor
public class MonthlyPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(nullable = false)
    private Integer dueDay;

    @Column(nullable = false)
    private Boolean active;

    @Column
    private String description;

    @Column
    private LocalDate nextDueDate;

    /**
     * User-supplied "subscribed since" date — when they actually signed up to the
     * service. Independent of createdAt (when they added the row to the tracker).
     * Nullable so existing rows remain valid under ddl-auto=update; the response
     * falls back to createdAt's date when this is missing.
     */
    @Column(name = "subscribed_since")
    private LocalDate subscribedSince;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (active == null) active = true;
    }
}
