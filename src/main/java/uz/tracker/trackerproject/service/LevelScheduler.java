package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Evaluates the Level 5 run once a day, shortly after midnight in Tashkent, so a start or an end of
 * Level 5 is recorded in its month whichever page is opened — or none (LEVELS-ALLOCATION-SPEC §1.1:
 * a change is written only while its month is in progress). The pages that show the level evaluate
 * it too; the recorder writes only when a change is due, so running both is harmless.
 */
@Component
@RequiredArgsConstructor
public class LevelScheduler {

    static final ZoneId OWNER_ZONE = ZoneId.of("Asia/Tashkent");

    private final LevelService levelService;

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Tashkent")
    public void refreshDaily() {
        levelService.refreshQuietly(LocalDate.now(OWNER_ZONE));
    }
}
