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
 * Money moved from one plain investment to another (Investments page, Batch 78) — like a transfer
 * between cards, but between holdings. It writes no transaction: it is not spending, not income and
 * not a put-in for any month; the totals of put-in and of value across holdings stay the same.
 * The holding ids are plain columns with no foreign key: deleting a holding sets its side to null
 * and the stored names keep the row readable.
 */
@Entity
@Table(name = "investment_transfers")
@Getter @Setter @NoArgsConstructor
public class InvestmentTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_investment_id")
    private Long fromInvestmentId;

    @Column(name = "to_investment_id")
    private Long toInvestmentId;

    @Column(name = "from_name", nullable = false)
    private String fromName;

    @Column(name = "to_name", nullable = false)
    private String toName;

    /** The money moved (value). */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** The part of "put in" that travelled with it (proportional to the from-holding's growth). */
    @Column(name = "put_in_moved", nullable = false, precision = 19, scale = 4)
    private BigDecimal putInMoved;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(name = "transfer_date", nullable = false)
    private LocalDate transferDate;

    @Column
    private String note;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
