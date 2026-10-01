package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * The road to Level 5 — or back from it (LEVELS-ALLOCATION-SPEC §3.1): on Level 4, the run of ended
 * months with pay of 60M or more; on Level 5, the run of ended months under it. Null on Levels 1–3.
 */
@Getter @Builder
public class LevelRoad {
    /** 5 while on Level 4; the base level while on Level 5. */
    private Integer toward;
    private BigDecimal payThreshold;
    private Integer monthsNeeded;
    /** The run now counting, oldest first; empty when none. */
    private List<MonthPay> months;
    /** Pay for the month in progress — never counted yet. */
    private BigDecimal thisMonthSoFar;
    /** 'YYYY-MM': when it would apply if the run completes unbroken. */
    private String appliesFrom;

    @Getter @Builder
    public static class MonthPay {
        /** YYYY-MM. */
        private String month;
        private BigDecimal pay;
    }
}
