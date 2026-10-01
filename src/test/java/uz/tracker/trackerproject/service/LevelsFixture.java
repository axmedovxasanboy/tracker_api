package uz.tracker.trackerproject.service;

import org.springframework.test.util.ReflectionTestUtils;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.LevelChange;
import uz.tracker.trackerproject.entity.LevelRuleVersion;
import uz.tracker.trackerproject.entity.StableIncomeEntry;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.repository.LevelChangeRepository;
import uz.tracker.trackerproject.repository.LevelRuleVersionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AnalyticsFixture} with the levels' stores kept in memory and wired into the engine, plus a
 * real {@link LevelService}: the rules versions, the recorded Level 5 changes, and the monthly
 * income month by month — so a test can play a run of pay and read what every page reads.
 */
final class LevelsFixture {

    final AnalyticsFixture f = new AnalyticsFixture();
    final List<LevelRuleVersion> versions = new ArrayList<>();
    final List<LevelChange> changes = new ArrayList<>();
    final List<StableIncomeEntry> income = new ArrayList<>();
    final LevelService levels;
    /** Evaluates the Level 5 run for a day (what the controllers do before a read, for today only). */
    final LevelChangeRecorder recorder;
    final LevelChangeRepository changeRepo;
    private long nextId = 1;
    private Category salary;
    private Category bonus;

    LevelsFixture() {
        LevelRuleVersionRepository versionRepo = mock(LevelRuleVersionRepository.class);
        when(versionRepo.findAllByOrderByLevelAscFromMonthAsc()).thenAnswer(i -> versions.stream()
                .sorted(Comparator.comparing(LevelRuleVersion::getLevel).thenComparing(LevelRuleVersion::getFromMonth)).toList());
        when(versionRepo.findByLevelOrderByFromMonthAsc(any())).thenAnswer(i -> versions.stream()
                .filter(v -> v.getLevel().equals(i.getArgument(0))).sorted(Comparator.comparing(LevelRuleVersion::getFromMonth)).toList());
        when(versionRepo.findByLevelAndFromMonth(any(), any())).thenAnswer(i -> versions.stream()
                .filter(v -> v.getLevel().equals(i.getArgument(0)) && v.getFromMonth().equals(i.getArgument(1))).findFirst());
        when(versionRepo.countByLevel(any())).thenAnswer(i -> versions.stream().filter(v -> v.getLevel().equals(i.getArgument(0))).count());
        when(versionRepo.save(any(LevelRuleVersion.class))).thenAnswer(i -> {
            LevelRuleVersion v = i.getArgument(0);
            if (v.getId() == null) v.setId(nextId++);
            if (!versions.contains(v)) versions.add(v);
            return v;
        });
        doAnswer(i -> versions.remove(i.<LevelRuleVersion>getArgument(0))).when(versionRepo).delete(any(LevelRuleVersion.class));

        changeRepo = mock(LevelChangeRepository.class);
        when(changeRepo.findAllByOrderByMonthAscIdAsc()).thenAnswer(i -> changes.stream()
                .sorted(Comparator.comparing(LevelChange::getMonth).thenComparing(LevelChange::getId)).toList());
        when(changeRepo.findByMonth(any())).thenAnswer(i -> changes.stream()
                .filter(c -> c.getMonth().equals(i.getArgument(0))).toList());
        when(changeRepo.findById(any())).thenAnswer(i -> changes.stream()
                .filter(c -> Objects.equals(c.getId(), i.getArgument(0))).findFirst());
        when(changeRepo.save(any(LevelChange.class))).thenAnswer(i -> {
            LevelChange c = i.getArgument(0);
            if (c.getId() == null) c.setId(nextId++);
            if (!changes.contains(c)) changes.add(c);
            return c;
        });
        doAnswer(i -> changes.remove(i.<LevelChange>getArgument(0))).when(changeRepo).delete(any(LevelChange.class));

        ReflectionTestUtils.setField(f.overview, "levelRuleVersionRepository", versionRepo);
        ReflectionTestUtils.setField(f.overview, "levelChangeRepository", changeRepo);
        when(f.settingsService.stableIncomeSchedule()).thenAnswer(i ->
                StableIncomeSchedule.of(income, f.settings.getMonthlyStableIncome()));
        recorder = new LevelChangeRecorder(f.overview, changeRepo);
        levels = new LevelService(f.overview, versionRepo, changeRepo, recorder);
    }

    /** The monthly income from {@code month} on; the first call also starts tracking there. */
    void incomeFrom(YearMonth month, String amount) {
        if (f.settings.getAllocationTrackingStartMonth() == null) f.stableIncome(amount, month.atDay(1));
        income.removeIf(e -> e.getMonth().equals(month.atDay(1)));
        income.add(new StableIncomeEntry(month.atDay(1), new BigDecimal(amount)));
    }

    /** Salary for {@code month}, paid on its 5th. */
    Transaction salary(YearMonth month, String amount) {
        return f.ledger.income(month.atDay(5), amount, salaryCategory());
    }

    /** A bonus for {@code month}, paid on its 20th. */
    Transaction bonus(YearMonth month, String amount) {
        return f.ledger.income(month.atDay(20), amount, bonusCategory());
    }

    /** Salary paid on {@code on}, marked as {@code month}'s (a late salary). */
    Transaction lateSalary(YearMonth month, LocalDate on, String amount) {
        Transaction t = f.ledger.income(on, amount, salaryCategory());
        t.setSalaryMonth(month.atDay(1));
        return t;
    }

    /** A recorded change, as the recorder writes it. */
    LevelChange recorded(YearMonth from, String kind, int level, int previous) {
        LevelChange c = new LevelChange();
        c.setId(nextId++);
        c.setMonth(from.atDay(1));
        c.setKind(kind);
        c.setLevel(level);
        c.setPreviousLevel(previous);
        changes.add(c);
        return c;
    }

    private Category salaryCategory() {
        if (salary == null) {
            salary = f.category(10, "Salary", null);
            bonus = f.category(12, "Bonus", salary);
            bonus.setBonusIncome(true);
        }
        return salary;
    }

    private Category bonusCategory() {
        salaryCategory();
        return bonus;
    }

    Integer level(YearMonth month) {
        return f.overview.levelFor(month);
    }
}
