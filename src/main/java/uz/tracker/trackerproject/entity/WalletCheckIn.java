package uz.tracker.trackerproject.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A mid-month wallet check-in: the user's real balance for each wallet on one day, and the
 * everyday-spending adjustment that brought the app's figure into line with it.
 *
 * <p>It is the month close's reconciliation without the close. Reconciling once a month leaves
 * a month's worth of small unrecorded spending to surface as one large, unexplained gap; doing it
 * every few days keeps the balances the app shows close to what is really in the wallet. Unlike
 * a close it freezes nothing — the month stays open, and the adjustments are ordinary
 * EVERYDAY_SPENDING transactions that the close later builds on.
 */
@Entity
@Table(name = "wallet_check_ins")
@Getter @Setter @NoArgsConstructor
public class WalletCheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The day the balances were true on. The adjustments are dated to it. */
    @Column(name = "check_date", nullable = false)
    private LocalDate date;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "checkIn", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WalletCheckInLine> lines = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void addLine(WalletCheckInLine line) {
        line.setCheckIn(this);
        lines.add(line);
    }
}
