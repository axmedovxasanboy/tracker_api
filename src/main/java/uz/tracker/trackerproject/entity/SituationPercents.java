package uz.tracker.trackerproject.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** One situation's three percentages in a level's rules version. 0 = not asked. */
@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class SituationPercents {

    @Column(name = "donation_percent", nullable = false, precision = 5, scale = 1)
    private BigDecimal donation;

    @Column(name = "emergency_percent", nullable = false, precision = 5, scale = 1)
    private BigDecimal emergency;

    @Column(name = "investments_percent", nullable = false, precision = 5, scale = 1)
    private BigDecimal investments;
}
