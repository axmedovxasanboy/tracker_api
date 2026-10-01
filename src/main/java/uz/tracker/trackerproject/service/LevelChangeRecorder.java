package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.entity.LevelChange;
import uz.tracker.trackerproject.repository.LevelChangeRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Writes a start or an end of Level 5 when it is due (LEVELS-ALLOCATION-SPEC §1.1, §3.4) — and only
 * then: the run is read every time, a row is written only when a change from the current month is
 * due and not yet recorded, or taken back when a recorded one from the current month is no longer
 * due (a correction made that month). A change from a month that has ended is never touched again.
 * In a transaction of its own, so the read-only request around it stays read-only, and a failure here
 * never fails the read (LevelService.refreshQuietly).
 */
@Service
@RequiredArgsConstructor
public class LevelChangeRecorder {

    private final OverviewService overviewService;
    private final LevelChangeRepository changes;

    /** @return true when it wrote or removed a change */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean refresh(LocalDate today) {
        YearMonth current = YearMonth.from(today);
        OverviewService.Standing st = overviewService.standing(current, today);
        List<LevelChange> stored = changes.findByMonth(current.atDay(1));
        LevelChange recorded = stored == null || stored.isEmpty() ? null : stored.get(stored.size() - 1);
        String due = st.pending();
        if (recorded != null && due != null && due.equals(recorded.getKind())) return false;   // already recorded
        boolean changed = false;
        if (recorded != null) {                       // no longer due, or the other way now: take it back
            changes.delete(recorded);
            changes.flush();                          // before the insert below: IDENTITY inserts run at once, deletes wait
            changed = true;
        }
        if (due != null) {
            Integer base = overviewService.baseLevelFor(current);
            LevelChange c = new LevelChange();
            c.setMonth(current.atDay(1));
            c.setKind(due);
            boolean up = LevelChange.UP.equals(due);
            // Without an income this month the base level reads as 0 left after bills: Level 1. (A null
            // here would fail the NOT NULL insert on every request.)
            if (base == null) base = OverviewService.baseLevelOf(BigDecimal.ZERO.subtract(overviewService.activeBillsUzs()));
            c.setLevel(up ? SavingsRules.TOP_LEVEL : base);
            c.setPreviousLevel(up ? previousBase(current) : SavingsRules.TOP_LEVEL);
            changes.save(c);
            changed = true;
        }
        return changed;
    }

    /** The level the month before was on, by income − bills (a start of Level 5 comes from Level 4). */
    private Integer previousBase(YearMonth current) {
        Integer base = overviewService.baseLevelFor(current.minusMonths(1));
        return base != null ? base : OverviewService.TOP_BASE_LEVEL;
    }
}
