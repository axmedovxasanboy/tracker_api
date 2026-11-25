package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.response.RecalcResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.MonthRule;
import uz.tracker.trackerproject.repository.MonthRuleRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The owner's "Recalculate" button (RECALC-SPEC, Batch 76) — the one deliberate exception to "a month's
 * rule is fixed on its 1st and never moves" (Batch 72). GET previews this month's rule as it stands
 * beside the one today's data gives ({@link OverviewService#ruleNow}); POST stores the latter as the
 * month's {@link MonthRule} with {@code recalculatedAt}. Only the owner's month under way; nothing
 * changes by itself, and the next month's 1st decides the next month as usual
 * ({@link MonthRuleRecorder} never rewrites a stored row).
 */
@Service
@RequiredArgsConstructor
public class RuleRecalcService {

    static final String NO_INCOME = "Set the monthly income first.";
    static final String NOT_THIS_MONTH = "Only this month's savings rule can be recalculated.";

    private final OverviewService overviewService;
    private final MonthRuleRepository rules;

    /** The owner's today (Asia/Tashkent) — the default day of the recalculate endpoints. */
    public static LocalDate ownerToday() {
        return LocalDate.now(LevelScheduler.OWNER_ZONE);
    }

    @Transactional(readOnly = true)
    public RecalcResponse preview(LocalDate date) {
        YearMonth month = checkedMonth(date);
        OverviewService.FixedRule current = currentRule(month);
        OverviewService.FixedRule now = overviewService.ruleNow(month, date);
        if (current == null || now == null) throw new IllegalArgumentException(NO_INCOME);
        return response(month, date, current, now, overviewService.ruleRecalculatedAt(month));
    }

    @Transactional
    public RecalcResponse apply(LocalDate date) {
        YearMonth month = checkedMonth(date);
        OverviewService.FixedRule current = currentRule(month);
        OverviewService.FixedRule now = overviewService.ruleNow(month, date);
        if (current == null || now == null) throw new IllegalArgumentException(NO_INCOME);
        LocalDateTime at = overviewService.ruleRecalculatedAt(month);
        if (!same(current, now)) {
            MonthRule row = rules.findByMonth(month.atDay(1)).orElseGet(() -> {
                MonthRule r = new MonthRule();
                r.setMonth(month.atDay(1));
                return r;
            });
            row.setLevel(now.level());
            row.setSituation(now.situation());
            at = LocalDateTime.now(LevelScheduler.OWNER_ZONE).truncatedTo(ChronoUnit.SECONDS);
            row.setRecalculatedAt(at);
            rules.save(row);
        }
        return response(month, date, now, now, at);
    }

    /**
     * The month of {@code date}, which must be the owner's month under way: a past month's rule never
     * moves, and a month to come has nothing to recalculate yet.
     */
    private static YearMonth checkedMonth(LocalDate date) {
        YearMonth month = YearMonth.from(date);
        if (!month.equals(OverviewService.ownerMonth())) throw new IllegalArgumentException(NOT_THIS_MONTH);
        return month;
    }

    /** The month's rule as it stands: the stored one, else what its 1st decides. */
    private OverviewService.FixedRule currentRule(YearMonth month) {
        OverviewService.FixedRule fixed = overviewService.fixedRule(month);
        return fixed != null ? fixed : overviewService.ruleAtStart(month);
    }

    private static boolean same(OverviewService.FixedRule a, OverviewService.FixedRule b) {
        return a.level() == b.level() && Objects.equals(a.situation(), b.situation());
    }

    private RecalcResponse response(YearMonth month, LocalDate date, OverviewService.FixedRule current,
                                    OverviewService.FixedRule now, LocalDateTime at) {
        SavingsRules.Book book = overviewService.ruleBook();
        List<Investment> own = overviewService.customSavings();
        // `current` was decided on the 1st — or, once recalculated, with that day's data.
        LocalDate currentAsOf = at != null ? at.toLocalDate() : month.atDay(1);
        LocalDate currentCut = at != null ? currentAsOf.plusDays(1) : month.atDay(1);
        String currentBasis = at != null ? BASIS_RECALCULATED : BASIS_MONTH_START;
        RecalcResponse.Rule cur = rule(book, month, current, own,
                why(overviewService.traceAsOf(month, currentCut), current, currentBasis, currentAsOf));
        RecalcResponse.Rule today = rule(book, month, now, own,
                why(overviewService.traceAsOf(month, date.plusDays(1)), now, BASIS_TODAY, date));
        return RecalcResponse.builder()
                .month(month.toString())
                .current(cur)
                .recalculated(today)
                .changed(!same(current, now))
                .recalculatedAt(at == null ? null : at.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    static final String BASIS_MONTH_START = "MONTH_START";
    static final String BASIS_RECALCULATED = "RECALCULATED";
    static final String BASIS_TODAY = "TODAY";

    /** The trace as the page's "why"; exact = it reproduces {@code shown}. Null without a trace. */
    static RecalcResponse.Why why(OverviewService.RuleTrace t, OverviewService.FixedRule shown,
                                  String basis, LocalDate asOf) {
        if (t == null) return null;
        List<RecalcResponse.BankLoanLine> banks = t.bankLoans().stream()
                .map(b -> RecalcResponse.BankLoanLine.builder().id(b.id()).name(b.name()).monthly(b.monthly()).build())
                .toList();
        List<RecalcResponse.LoanLine> loans = t.loans().stream()
                .map(l -> RecalcResponse.LoanLine.builder().kind(l.kind()).id(l.id()).name(l.name()).type(l.type())
                        .left(l.left()).ask(l.ask()).note(l.note())
                        .dueDate(l.dueDate() == null ? null : l.dueDate().toString()).build())
                .toList();
        return RecalcResponse.Why.builder()
                .basis(basis)
                .asOf(asOf.toString())
                .exact(same(t.rule(), shown))
                .income(t.income()).bills(t.bills()).leftAfterBills(t.leftAfterBills())
                .levelFrom(t.levelFrom()).levelTo(t.levelTo())
                .level5Since(t.level5Since() == null ? null : t.level5Since().toString())
                .bankLoans(banks).bankMonthly(t.bankMonthly())
                .loans(loans)
                .asksTotal(t.asksTotal()).monthlyAsks(t.monthlyAsks())
                .smallMonthlyLimit(t.smallMonthlyLimit()).smallMonthlyIgnored(t.smallMonthlyIgnored())
                .peopleDebtForRule(t.peopleDebtForRule())
                .debtShare(t.debtRatio().multiply(new BigDecimal("100")).setScale(1, java.math.RoundingMode.HALF_UP))
                .heavyLine(OverviewService.HEAVY_LINE_PERCENT)
                .leftAfterLoans(t.leftAfterLoans())
                .cutoff(t.cutoff())
                .hasBank(t.hasBank()).hasPeopleDebt(t.hasPeopleDebt())
                .build();
    }

    private static RecalcResponse.Rule rule(SavingsRules.Book book, YearMonth month,
                                            OverviewService.FixedRule r, List<Investment> own,
                                            RecalcResponse.Why why) {
        SavingsRules.Version v = book.version(r.level(), month);
        SavingsRules.Percents p = v.percents(r.situation());
        BigDecimal donation = nz(p == null ? null : p.donation());
        BigDecimal emergency = nz(p == null ? null : p.emergency());
        BigDecimal investments = nz(p == null ? null : p.investments());
        BigDecimal total = donation.add(emergency).add(investments);
        List<RecalcResponse.Own> lines = new ArrayList<>(own.size());
        for (Investment s : own) {
            BigDecimal pct = nz(v.custom(r.situation()).get(s.getId()));
            total = total.add(pct);
            lines.add(RecalcResponse.Own.builder().id(s.getId()).name(s.getName()).percent(pct).build());
        }
        return RecalcResponse.Rule.builder()
                .level(r.level())
                .situation(r.situation())
                .cutoff(v.cutoff())
                .percents(RecalcResponse.Percents.builder()
                        .donation(donation).emergency(emergency).investments(investments).build())
                .own(lines)
                .total(total)
                .why(why)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
