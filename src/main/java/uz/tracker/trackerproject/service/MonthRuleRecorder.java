package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.entity.MonthRule;
import uz.tracker.trackerproject.repository.MonthRuleRepository;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Stores the month's savings rule — its level and situation — the first time the month is read or
 * the daily run passes it (shortly after midnight on the 1st), as its first day decides it
 * ({@link OverviewService#ruleAtStart}). Never rewritten after: the owner's rule (2026-10-15) is that
 * a month's distribution is set when it begins and never changes. A month without an income is left
 * for the first read after the income is set. In a transaction of its own, like the Level 5 recorder,
 * so the read-only request around it stays read-only.
 */
@Service
@RequiredArgsConstructor
public class MonthRuleRecorder {

    private final OverviewService overviewService;
    private final MonthRuleRepository rules;

    /** @return true when it stored the month's rule */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fix(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        if (rules.findByMonth(month.atDay(1)).isPresent()) return false;
        OverviewService.FixedRule rule = overviewService.ruleAtStart(month);
        if (rule == null) return false;
        MonthRule row = new MonthRule();
        row.setMonth(month.atDay(1));
        row.setLevel(rule.level());
        row.setSituation(rule.situation());
        rules.save(row);
        return true;
    }
}
