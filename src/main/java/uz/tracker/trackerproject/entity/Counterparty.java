package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.CounterpartyKind;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * A person (or a shop, a bank app) the owner borrows from or lends to, so repeat borrowing from the
 * same person adds up in one place. Lenders and borrowers are separate lists: the same name can be
 * both, as two rows. Within a list a name is unique ignoring case and surrounding spaces
 * ({@link #nameKey}).
 *
 * <p>The records keep their own name columns (LoanTaken.lenderName, Debt.creditorName,
 * LoanGiven.debtorName) — the bot shows those — and link here by id.
 */
@Entity
@Table(name = "counterparties",
        uniqueConstraints = @UniqueConstraint(name = "uk_counterparty_kind_name", columnNames = {"kind", "name_key"}))
@Getter @Setter @NoArgsConstructor
public class Counterparty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** {@link #keyOf}(name): what two names must share to be the same person. */
    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CounterpartyKind kind;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /** The name as matched: trimmed and lower-cased; null for a blank name. */
    public static String keyOf(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
