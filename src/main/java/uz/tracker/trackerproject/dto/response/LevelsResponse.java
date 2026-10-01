package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/v1/levels} (LEVELS-ALLOCATION-SPEC §3.1): the level this month is on and why, the
 * road to or from Level 5, and every level's savings rules, version by version. Money is UZS; months
 * are 'YYYY-MM'.
 */
@Getter @Builder
public class LevelsResponse {
    /** The month of {@code date}. */
    private String month;
    /** In force this month (1..5); null without an income. */
    private Integer level;
    /** From income − bills alone (1..4); null without an income. */
    private Integer baseLevel;
    private BigDecimal leftAfterBills;
    /** One of the seven situation keys; null without an income. */
    private String situation;
    /** In force this month; null without an income. */
    private Percents percents;
    /** 'YYYY-MM' while on Level 5. */
    private String level5Since;
    /** Null on Levels 1–3. */
    private LevelRoad road;
    /** The earliest {@code from} a version may take. */
    private String firstMonth;
    /** Always five entries. */
    private List<Level> levels;

    @Getter @Builder
    public static class Percents {
        private BigDecimal donation;
        private BigDecimal emergency;
        private BigDecimal investments;
    }

    @Getter @Builder
    public static class Level {
        private int level;
        /** The left-after-bills band; Level 4: leftTo null; Level 5: both null. */
        private BigDecimal leftFrom;
        private BigDecimal leftTo;
        /** {@code from} of the version in force this month. */
        private String inForce;
        /** Oldest first; never empty. */
        private List<Version> versions;
    }

    @Getter @Builder
    public static class Version {
        private String from;
        private BigDecimal cutoff;
        /** The seven situations, in their fixed order. */
        private Map<String, Percents> rules;
    }
}
