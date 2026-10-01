package uz.tracker.trackerproject.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

/**
 * {@code PUT /api/v1/levels/{level}/rules} (LEVELS-ALLOCATION-SPEC §3.2): one change to a level's
 * rules, from a month on. Only what changes is sent.
 */
@Getter @Setter
public class LevelRulesRequest {
    /** 'YYYY-MM'; absent → the current month. */
    private String from;
    /** Absent → the cutoff in force at {@code from}. */
    private BigDecimal cutoff;
    /** Situation key → its percentages; a percentage left out keeps the one in force at {@code from}. */
    private Map<String, Percents> rules;

    @Getter @Setter
    public static class Percents {
        private BigDecimal donation;
        private BigDecimal emergency;
        private BigDecimal investments;
    }
}
