package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET / POST /api/v1/levels/recalculate} (RECALC-SPEC §1, Batch 76): this month's savings rule
 * as it stands ({@link #current} — what its 1st fixed, or the owner's last recalculation) beside the
 * rule today's data gives ({@link #recalculated}). Percentages are whole percents of the savings base.
 */
@Getter @Builder
public class RecalcResponse {
    /** 'YYYY-MM'. */
    private String month;
    private Rule current;
    private Rule recalculated;
    /** The level or the situation differs. */
    private boolean changed;
    /** ISO local date-time (Asia/Tashkent) of the owner's last recalculation this month; null when never. */
    private String recalculatedAt;

    @Getter @Builder
    public static class Rule {
        private int level;
        /** One of {@code SavingsRules.SITUATIONS}. */
        private String situation;
        /** The tight / comfortable split line of this level's rules version in force that month; may be null. */
        private BigDecimal cutoff;
        private Percents percents;
        /** Every own saving (Batch 74) with its percentage in this rule, 0 allowed; same order in both blocks. */
        private List<Own> own;
        /** The three plus every own saving. */
        private BigDecimal total;
    }

    @Getter @Builder
    public static class Percents {
        private BigDecimal donation;
        private BigDecimal emergency;
        private BigDecimal investments;
    }

    @Getter @Builder
    public static class Own {
        private Long id;
        private String name;
        private BigDecimal percent;
    }
}
