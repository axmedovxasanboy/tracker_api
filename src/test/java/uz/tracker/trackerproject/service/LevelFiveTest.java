package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.LevelNoticeResponse;
import uz.tracker.trackerproject.dto.response.LevelRoad;
import uz.tracker.trackerproject.dto.response.LevelsResponse;
import uz.tracker.trackerproject.dto.response.ProfileResponse;
import uz.tracker.trackerproject.entity.LevelChange;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Level 5 (LEVELS-ALLOCATION-SPEC §1.1, §3.6): earned by pay of 60M or more three ended months in a
 * row while on Level 4, kept by pay, left after three ended months in a row under it — recorded once
 * as a change, re-checked only while its month is in progress, and announced once per client.
 *
 * <p>The owner here earns 50,000,000 a month (Level 4: 45M or more left after bills), tracking from
 * June 2026; each month's pay is set by the test.
 */
class LevelFiveTest {

    private static final YearMonth JUN = YearMonth.of(2026, 6);
    private static final YearMonth JUL = YearMonth.of(2026, 7);
    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final YearMonth DEC = YearMonth.of(2026, 12);
    private static final YearMonth JAN = YearMonth.of(2027, 1);

    private LevelsFixture lf;

    @BeforeEach
    void setUp() {
        lf = new LevelsFixture();
        lf.incomeFrom(JUN, "50000000");
    }

    /** 61,000,000 of pay for each month: salary 50M + a bonus of 11M. */
    private void qualifies(YearMonth... months) {
        for (YearMonth m : months) {
            lf.salary(m, "50000000");
            lf.bonus(m, "11000000");
        }
    }

    private boolean refresh(LocalDate today) {
        return lf.recorder.refresh(today);
    }

    @Test
    void threeQualifyingMonthsMakeLevel5FromTheNextMonth_recordedOnce() {
        qualifies(JUN, JUL, AUG);
        assertThat(lf.level(SEP)).isEqualTo(4);                       // nothing recorded yet

        assertThat(refresh(SEP.atDay(10))).isTrue();

        assertThat(lf.changes).extracting(c -> YearMonth.from(c.getMonth()), LevelChange::getKind, LevelChange::getLevel,
                LevelChange::getPreviousLevel).containsExactly(tuple(SEP, "UP", 5, 4));
        assertThat(lf.level(AUG)).isEqualTo(4);                       // the months that earned it keep their level
        assertThat(lf.level(SEP)).isEqualTo(5);
        assertThat(lf.level(OCT)).isEqualTo(5);                       // and it stays
        // Every read after it asks nothing more of the database.
        assertThat(refresh(SEP.atDay(11))).isFalse();
        assertThat(lf.changes).hasSize(1);
        // The tier, and so every page, reads Level 5 — with Level 5's rules (seeded from Level 1's numbers).
        assertThat(lf.f.overview.getTierIgnoringSubscriptions(SEP, Currency.UZS, SEP.atDay(10)).getLevel()).isEqualTo(5);
        assertThat(lf.f.overview.getTierIgnoringSubscriptions(SEP, Currency.UZS, SEP.atDay(10)).getAllocation().getScenarioKey())
                .isEqualTo("5.1");
    }

    @Test
    void aMonthUnder60MInBetweenResetsTheRun() {
        qualifies(JUN, AUG);
        lf.salary(JUL, "50000000");                                   // 50M: under the line

        assertThat(refresh(SEP.atDay(10))).isFalse();
        assertThat(lf.level(SEP)).isEqualTo(4);
        LevelRoad road = lf.levels.levels(SEP.atDay(10)).getRoad();
        assertThat(road.getToward()).isEqualTo(5);
        assertThat(road.getMonths()).extracting(LevelRoad.MonthPay::getMonth).containsExactly("2026-08");
        assertThat(road.getAppliesFrom()).isEqualTo("2026-11");       // September and October still to come
    }

    @Test
    void aMonthOnLevel3InBetweenResetsTheRun() {
        qualifies(JUN, JUL, AUG);
        lf.incomeFrom(JUL, "35000000");                               // July: 35M left after bills → Level 3
        lf.incomeFrom(AUG, "50000000");

        assertThat(refresh(SEP.atDay(10))).isFalse();
        assertThat(lf.levels.levels(SEP.atDay(10)).getRoad().getMonths()).extracting(LevelRoad.MonthPay::getMonth)
                .containsExactly("2026-08");
    }

    @Test
    void aMonthInProgressNeverCounts_howeverMuchPayItAlreadyHas() {
        qualifies(JUN, JUL);
        lf.salary(AUG, "70000000");

        assertThat(refresh(AUG.atDay(20))).isFalse();
        assertThat(lf.level(AUG)).isEqualTo(4);
        LevelsResponse r = lf.levels.levels(AUG.atDay(20));
        assertThat(r.getRoad().getMonths()).extracting(LevelRoad.MonthPay::getMonth, m -> m.getPay().toPlainString())
                .containsExactly(tuple("2026-06", "61000000"), tuple("2026-07", "61000000"));
        assertThat(r.getRoad().getThisMonthSoFar()).isEqualByComparingTo("70000000");
        assertThat(r.getRoad().getAppliesFrom()).isEqualTo("2026-09");
    }

    /** August's salary paid on 10 September, marked as August's: the run completes then, and September turns Level 5. */
    @Test
    void aLateSalaryMarkedForTheThirdMonthStartsLevel5InTheMonthItIsRecorded() {
        qualifies(JUN, JUL);
        lf.salary(AUG, "10000000");
        assertThat(refresh(SEP.atDay(3))).isFalse();

        lf.lateSalary(AUG, SEP.atDay(10), "55000000");                // 65M for August now
        assertThat(refresh(SEP.atDay(10))).isTrue();

        assertThat(lf.level(SEP)).isEqualTo(5);
        assertThat(lf.changes).extracting(LevelChange::getKind).containsExactly("UP");
    }

    /** Recorded even later — in October, for August: September has ended and stands; Level 5 starts with October. */
    @Test
    void aSalaryFixedAfterTheNextMonthEndedStartsLevel5WithTheMonthItIsRecordedIn() {
        qualifies(JUN, JUL, SEP);
        lf.salary(AUG, "10000000");
        lf.lateSalary(AUG, OCT.atDay(4), "55000000");

        assertThat(refresh(OCT.atDay(4))).isTrue();

        assertThat(lf.level(SEP)).isEqualTo(4);                       // an ended month is never rewritten
        assertThat(lf.level(OCT)).isEqualTo(5);
    }

    @Test
    void aCorrectionWithinTheStartMonthTakesItBack_afterThatItStands() {
        qualifies(JUN, JUL);
        Transaction august = lf.salary(AUG, "50000000");
        Transaction twice = lf.bonus(AUG, "11000000");                // a bonus entered twice: 72M
        lf.bonus(AUG, "11000000");
        refresh(SEP.atDay(10));
        assertThat(lf.level(SEP)).isEqualTo(5);

        twice.setAmount(java.math.BigDecimal.ZERO);                   // put right in September: 61M, still enough
        assertThat(refresh(SEP.atDay(12))).isFalse();
        august.setAmount(new java.math.BigDecimal("48000000"));       // and the salary too: 59M — not enough
        assertThat(refresh(SEP.atDay(14))).isTrue();
        assertThat(lf.changes).isEmpty();
        assertThat(lf.level(SEP)).isEqualTo(4);

        // Back to 61M, and September ends: a later edit to August's pay no longer moves September.
        august.setAmount(new java.math.BigDecimal("50000000"));
        refresh(SEP.atDay(20));
        assertThat(lf.level(SEP)).isEqualTo(5);
        august.setAmount(new java.math.BigDecimal("10000000"));
        qualifies(SEP);
        assertThat(refresh(OCT.atDay(2))).isFalse();
        assertThat(lf.level(SEP)).isEqualTo(5);
        assertThat(lf.changes).hasSize(1);
    }

    @Test
    void threeMonthsUnder60MOnLevel5GoBackToTheBaseLevelFromTheNextMonth() {
        lf.recorded(SEP, LevelChange.UP, 5, 4);
        for (YearMonth m : new YearMonth[]{SEP, OCT, NOV}) lf.salary(m, "30000000");

        assertThat(refresh(DEC.atDay(5))).isTrue();

        assertThat(lf.changes).extracting(c -> YearMonth.from(c.getMonth()), LevelChange::getKind, LevelChange::getLevel,
                LevelChange::getPreviousLevel).containsExactly(tuple(SEP, "UP", 5, 4), tuple(DEC, "DOWN", 4, 5));
        assertThat(lf.level(NOV)).isEqualTo(5);
        assertThat(lf.level(DEC)).isEqualTo(4);
        // Back on Level 4, reaching Level 5 again takes a new run of three.
        assertThat(lf.levels.levels(DEC.atDay(5)).getRoad().getMonths()).isEmpty();
    }

    @Test
    void aMonthWith60MOrMoreResetsTheRunDown() {
        lf.recorded(SEP, LevelChange.UP, 5, 4);
        lf.salary(SEP, "30000000");
        qualifies(OCT);
        lf.salary(NOV, "30000000");

        assertThat(refresh(DEC.atDay(5))).isFalse();
        LevelRoad road = lf.levels.levels(DEC.atDay(5)).getRoad();
        assertThat(road.getToward()).isEqualTo(4);
        assertThat(road.getMonths()).extracting(LevelRoad.MonthPay::getMonth).containsExactly("2026-11");
        assertThat(lf.levels.levels(DEC.atDay(5)).getLevel5Since()).isEqualTo("2026-09");
    }

    /** A way down due in a month with no income set: the level is the one 0 left after bills gives — Level 1. */
    @Test
    void aWayDownInAMonthWithNoIncomeGoesToLevel1() {
        lf.recorded(SEP, LevelChange.UP, 5, 4);
        for (YearMonth m : new YearMonth[]{SEP, OCT, NOV}) lf.salary(m, "30000000");
        lf.incomeFrom(DEC, "0");

        assertThat(refresh(DEC.atDay(5))).isTrue();

        assertThat(lf.changes).extracting(LevelChange::getKind, LevelChange::getLevel)
                .containsExactly(tuple("UP", 5), tuple("DOWN", 1));
    }

    /** A recorded change of the other kind is taken back and flushed before the new one is inserted. */
    @Test
    void aTakeBackIsFlushedBeforeTheNewChangeIsInserted() {
        qualifies(JUN, JUL, AUG);
        lf.recorded(SEP, LevelChange.DOWN, 4, 5);

        assertThat(refresh(SEP.atDay(10))).isTrue();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(lf.changeRepo);
        order.verify(lf.changeRepo).delete(org.mockito.ArgumentMatchers.any(LevelChange.class));
        order.verify(lf.changeRepo).flush();
        order.verify(lf.changeRepo).save(org.mockito.ArgumentMatchers.any(LevelChange.class));
        assertThat(lf.changes).extracting(LevelChange::getKind).containsExactly("UP");
    }

    /** Only pay ends Level 5: the income dropping to Level 2's band does not — and the way down then goes to Level 2. */
    @Test
    void loweringTheIncomeDoesNotEndLevel5() {
        lf.recorded(SEP, LevelChange.UP, 5, 4);
        lf.incomeFrom(OCT, "20000000");
        qualifies(SEP, OCT, NOV);

        assertThat(refresh(DEC.atDay(5))).isFalse();
        assertThat(lf.level(DEC)).isEqualTo(5);
        assertThat(lf.levels.levels(DEC.atDay(5)).getBaseLevel()).isEqualTo(2);

        for (YearMonth m : new YearMonth[]{DEC, JAN}) lf.salary(m, "20000000");
        lf.salary(YearMonth.of(2027, 2), "20000000");
        assertThat(refresh(YearMonth.of(2027, 3).atDay(1))).isTrue();
        assertThat(lf.level(YearMonth.of(2027, 3))).isEqualTo(2);
    }

    // ── The notice ────────────────────────────────────────────────────────────

    @Test
    void oneNoticePerChange_seenSeparatelyByTheWebAndTheBot() {
        qualifies(JUN, JUL, AUG);
        refresh(SEP.atDay(10));

        LevelNoticeResponse web = lf.levels.notice("WEB").orElseThrow();
        assertThat(web.getKind()).isEqualTo("UP");
        assertThat(web.getLevel()).isEqualTo(5);
        assertThat(web.getPreviousLevel()).isEqualTo(4);
        assertThat(web.getFrom()).isEqualTo("2026-09");
        assertThat(web.getMonths()).extracting(LevelRoad.MonthPay::getMonth, m -> m.getPay().toPlainString())
                .containsExactly(tuple("2026-06", "61000000"), tuple("2026-07", "61000000"), tuple("2026-08", "61000000"));
        assertThat(web.getSituation()).isEqualTo("NO_DEBT");
        assertThat(web.getPercents().getInvestments()).isEqualByComparingTo("15");
        assertThat(web.getAmounts().getDonation()).isEqualByComparingTo("5000000");   // 10% of 50,000,000
        assertThat(web.getAmounts().getInvestments()).isEqualByComparingTo("7500000");

        lf.levels.seen(web.getId(), "WEB");
        assertThat(lf.levels.notice("WEB")).isEmpty();
        assertThat(lf.levels.notice("BOT")).map(LevelNoticeResponse::getId).contains(web.getId());
        lf.levels.seen(web.getId(), "BOT");
        assertThat(lf.levels.notice("BOT")).isEmpty();

        // The way down later is a notice of its own; the oldest unseen comes first.
        lf.recorded(DEC, LevelChange.DOWN, 4, 5);
        lf.recorded(YearMonth.of(2027, 6), LevelChange.UP, 5, 4);
        assertThat(lf.levels.notice("WEB")).map(LevelNoticeResponse::getKind).contains("DOWN");
        assertThatThrownBy(() -> lf.levels.notice("PHONE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lf.levels.seen(999L, "WEB"))
                .isInstanceOf(uz.tracker.trackerproject.exception.ResourceNotFoundException.class);
    }

    /** Profile carries the same: Level 5 since, and the road back. */
    @Test
    void profileCarriesTheLevelAndTheRoad() {
        qualifies(JUN, JUL, AUG);
        refresh(SEP.atDay(10));
        lf.salary(SEP, "30000000");

        ProfileResponse p = new ProfileService(lf.f.overview, mockTransactions()).profile(OCT.atDay(5), "owner");

        assertThat(p.getLevel()).isEqualTo(5);
        assertThat(p.getBaseLevel()).isEqualTo(4);
        assertThat(p.getLevel5Since()).isEqualTo("2026-09");
        assertThat(p.getNextLevelAt()).isNull();
        assertThat(p.getLevelFrom()).isEqualByComparingTo("45000000");
        assertThat(p.getRoad().getToward()).isEqualTo(4);
        assertThat(p.getRoad().getMonths()).extracting(LevelRoad.MonthPay::getMonth).containsExactly("2026-09");
        assertThat(p.getRuleFrom()).isEqualTo("2026-06");
    }

    private uz.tracker.trackerproject.repository.TransactionRepository mockTransactions() {
        return (uz.tracker.trackerproject.repository.TransactionRepository)
                org.springframework.test.util.ReflectionTestUtils.getField(lf.f.overview, "transactionRepository");
    }

    @Test
    void withoutAnIncomeThereIsNoLevel() {
        LevelsFixture empty = new LevelsFixture();
        LevelsResponse r = empty.levels.levels(SEP.atDay(10));
        assertThat(r.getLevel()).isNull();
        assertThat(r.getSituation()).isNull();
        assertThat(r.getRoad()).isNull();
        assertThat(r.getLevels()).hasSize(5);
        assertThat(Optional.ofNullable(r.getPercents())).isEmpty();
    }
}
