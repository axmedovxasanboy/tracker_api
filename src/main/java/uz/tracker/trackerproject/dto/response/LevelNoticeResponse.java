package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * {@code GET /api/v1/levels/notice} (LEVELS-ALLOCATION-SPEC §3.4): the oldest start (UP) or end (DOWN)
 * of Level 5 the client has not seen — the web's dialog, the bot's message.
 */
@Getter @Builder
public class LevelNoticeResponse {
    private Long id;
    /** UP | DOWN. */
    private String kind;
    /** The level from {@link #from}. */
    private Integer level;
    private Integer previousLevel;
    /** 'YYYY-MM' it applies from. */
    private String from;
    /** The three ended months that decided it, with their pay. */
    private List<LevelRoad.MonthPay> months;
    /** The situation for the {@code from} month. */
    private String situation;
    /** The level's percentages for that situation in the {@code from} month. */
    private LevelsResponse.Percents percents;
    /** Those percentages × the income for {@code from}, to the so'm. */
    private LevelsResponse.Percents amounts;
}
