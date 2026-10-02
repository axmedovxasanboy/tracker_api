package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Notes down every holding's month and the position once a day, shortly after midnight in Tashkent
 * ({@link HoldingSnapshotService}, ANALYTICS-V2-SPEC §4.6) — whether or not any page is opened, so
 * no day is lost: a past month's value cannot be rebuilt later. Five minutes after
 * {@link LevelScheduler}, on the same one-thread scheduler (SchedulingConfig).
 *
 * <p>It never runs twice at once: the scheduler does not start a cron task again before its run has
 * finished, and a run that finds another one still going (a call by hand) does nothing. Each of the
 * two writes is a transaction of its own; a failure is logged and waits for the next day — it is
 * never retried in a loop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotScheduler {

    private final HoldingSnapshotService snapshots;
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(cron = "0 15 0 * * *", zone = "Asia/Tashkent")
    public void snapshotDaily() {
        run(LocalDate.now(LevelScheduler.OWNER_ZONE));
    }

    /** One run for the owner's {@code today}; false when another run was still going. */
    boolean run(LocalDate today) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Snapshots for {} skipped: the previous run is still going", today);
            return false;
        }
        try {
            try {
                snapshots.recordHoldings(today);
            } catch (RuntimeException e) {
                log.warn("Could not record the holdings' snapshots for {}: {}", today, e.toString());
            }
            try {
                snapshots.recordPosition(today);
            } catch (RuntimeException e) {
                log.warn("Could not record the position for {}: {}", today, e.toString());
            }
            return true;
        } finally {
            running.set(false);
        }
    }
}
