package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import uz.tracker.trackerproject.controller.AnalyticsController;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A start or an end of Level 5 is recorded in its month whichever page is opened, or none: once a day
 * after midnight in Tashkent, and before Analytics is read.
 */
class LevelSchedulerTest {

    @Test
    void theDailyRunEvaluatesTheLevelFor10PastMidnightInTashkent() throws Exception {
        LevelService levels = mock(LevelService.class);

        new LevelScheduler(levels).refreshDaily();

        verify(levels).refreshQuietly(any(LocalDate.class));
        Scheduled s = LevelScheduler.class.getMethod("refreshDaily").getAnnotation(Scheduled.class);
        assertThat(s.cron()).isEqualTo("0 10 0 * * *");
        assertThat(s.zone()).isEqualTo("Asia/Tashkent");
        assertThat(LevelScheduler.OWNER_ZONE).isEqualTo(ZoneId.of("Asia/Tashkent"));
    }

    @Test
    void analyticsEvaluatesTheLevelForTheOwnersDayBeforeItIsRead() {
        AnalyticsService analytics = mock(AnalyticsService.class);
        LevelService levels = mock(LevelService.class);
        AnalyticsController controller = new AnalyticsController(analytics);
        ReflectionTestUtils.setField(controller, "levelService", levels);

        controller.analytics(null, null, "2026-10-01");

        verify(levels).refreshQuietly(eq(LocalDate.of(2026, 10, 1)));
    }
}
