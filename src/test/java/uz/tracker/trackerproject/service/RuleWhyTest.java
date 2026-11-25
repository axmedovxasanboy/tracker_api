package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uz.tracker.trackerproject.dto.response.RecalcResponse;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MonthRule;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.MonthRuleRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Nega?" (RECALC-SPEC §4, Batch 77): every figure the month's rule was decided with, from the same
 * code path that decides it — so the page's reasons can never disagree with the rule.
 */
class RuleWhyTest {

    private final YearMonth now = OverviewService.ownerMonth();
    private final LocalDate today = LocalDate.now(LevelScheduler.OWNER_ZONE);
    private final LocalDate lastMonth = now.minusMonths(1).atDay(20);
    private LevelsFixture fx;
    private final List<MonthRule> stored = new ArrayList<>();
    private MonthRuleRecorder recorder;
    private RuleRecalcService recalc;

    @BeforeEach
    void setUp() {
        fx = new LevelsFixture();
        MonthRuleRepository repo = mock(MonthRuleRepository.class);
        when(repo.findByMonth(any())).thenAnswer(i -> stored.stream()
                .filter(r -> r.getMonth().equals(i.getArgument(0))).findFirst());
        when(repo.findAll()).thenAnswer(i -> List.copyOf(stored));
        when(repo.save(any(MonthRule.class))).thenAnswer(i -> {
            MonthRule r = i.getArgument(0);
            if (r.getId() == null) {
                r.setId((long) stored.size() + 1);
                stored.add(r);
            }
            return r;
        });
        ReflectionTestUtils.setField(fx.f.overview, "monthRuleRepository", repo);
        recorder = new MonthRuleRecorder(fx.f.overview, repo);
        ReflectionTestUtils.setField(fx.levels, "monthRules", recorder);
        recalc = new RuleRecalcService(fx.f.overview, repo);
        fx.incomeFrom(now.minusMonths(3), "8000000");
    }

    private void bank(String monthly) {
        fx.f.bankLoan(1, "Xalq banki", "Kredit", "48000000", monthly, now.minusMonths(6).atDay(5), now.plusMonths(12).atDay(5));
    }

    private LoanTaken asap(long id, LocalDate on, String amount) {
        return fx.f.borrowed(id, "Ali", amount, "0", null, on, null);
    }

    private OverviewService.RuleTrace trace() {
        return fx.f.overview.traceAsOf(now, today.plusDays(1));
    }

    /** The trace's own figures give its rule, and it is the rule ruleAsOf returns. */
    private OverviewService.RuleTrace consistent() {
        OverviewService.RuleTrace t = trace();
        assertThat(t.rule()).isEqualTo(fx.f.overview.ruleNow(now, today));
        BigDecimal left = t.leftAfterBills().subtract(t.bankMonthly()).subtract(t.asksTotal());
        assertThat(t.leftAfterLoans()).isEqualByComparingTo(left.signum() < 0 ? BigDecimal.ZERO : left);
        assertThat(t.bankLoans().stream().map(OverviewService.TraceBank::monthly).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(t.bankMonthly());
        assertThat(t.loans().stream().map(OverviewService.TraceLoan::ask).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(t.asksTotal());
        return t;
    }

    @Test
    void noDebt() {
        OverviewService.RuleTrace t = consistent();
        assertThat(t.rule().situation()).isEqualTo(SavingsRules.NO_DEBT);
        assertThat(t.income()).isEqualByComparingTo("8000000");
        assertThat(t.levelFrom()).isEqualByComparingTo("0");
        assertThat(t.levelTo()).isEqualByComparingTo("15000000");
        assertThat(t.level5Since()).isNull();
        assertThat(t.hasBank()).isFalse();
        assertThat(t.hasPeopleDebt()).isFalse();
        assertThat(t.loans()).isEmpty();
    }

    @Test
    void bankLoanTightAndComfortable() {
        bank("4000000");
        OverviewService.RuleTrace t = consistent();
        assertThat(t.rule().situation()).isEqualTo(SavingsRules.BANK_LOAN_TIGHT);
        assertThat(t.leftAfterLoans()).isEqualByComparingTo("4000000");
        assertThat(t.leftAfterLoans()).isLessThan(t.cutoff());
        assertThat(t.bankLoans()).singleElement().satisfies(b -> assertThat(b.name()).contains("Xalq banki"));

        fx.f.bankLoans.getFirst().setMonthlyPayment(new BigDecimal("2000000"));
        t = consistent();
        assertThat(t.rule().situation()).isEqualTo(SavingsRules.BANK_LOAN_COMFORTABLE);
        assertThat(t.leftAfterLoans()).isGreaterThanOrEqualTo(t.cutoff());
    }

    @Test
    void debtsTightComfortableAndWithABank() {
        LoanTaken l = asap(7, lastMonth, "1000000");
        OverviewService.RuleTrace t = consistent();
        assertThat(t.rule().situation()).isEqualTo(SavingsRules.DEBTS_COMFORTABLE);
        assertThat(t.loans()).singleElement().satisfies(x -> {
            assertThat(x.type()).isEqualTo("ASAP");
            assertThat(x.ask()).isEqualByComparingTo("1000000");
            assertThat(x.note()).isNull();
        });
        assertThat(t.peopleDebtForRule()).isEqualByComparingTo("1000000");

        l.setTotalAmount(new BigDecimal("4000000"));
        assertThat(consistent().rule().situation()).isEqualTo(SavingsRules.DEBTS_TIGHT);

        l.setTotalAmount(new BigDecimal("1000000"));
        bank("2000000");
        t = consistent();
        assertThat(t.rule().situation()).isEqualTo(SavingsRules.BANK_AND_DEBTS);
        assertThat(t.hasBank()).isTrue();
        assertThat(t.hasPeopleDebt()).isTrue();
    }

    @Test
    void heavyDebtOverTheLine() {
        bank("4000000");
        asap(7, lastMonth, "2000000");
        OverviewService.RuleTrace t = consistent();
        assertThat(t.rule().situation()).isEqualTo(SavingsRules.HEAVY_DEBT);
        RecalcResponse.Why why = recalc.preview(today).getRecalculated().getWhy();
        assertThat(why.getDebtShare()).isEqualByComparingTo("75.0");
        assertThat(why.getHeavyLine()).isEqualByComparingTo("70");
        assertThat(why.getBasis()).isEqualTo(RuleRecalcService.BASIS_TODAY);
        assertThat(why.getAsOf()).isEqualTo(today.toString());
        assertThat(why.isExact()).isTrue();
    }

    @Test
    void aSmallMonthlyLoanIsListedButNotCountedForTheRule() {
        bank("2000000");
        fx.f.borrowed(9, "Ota-onam", "4500000", "0", "500000", lastMonth, now.minusMonths(1).atDay(1));
        OverviewService.RuleTrace t = consistent();
        assertThat(t.smallMonthlyIgnored()).isTrue();
        assertThat(t.smallMonthlyLimit()).isEqualByComparingTo("800000");
        assertThat(t.monthlyAsks()).isEqualByComparingTo("500000");
        assertThat(t.peopleDebtForRule()).isEqualByComparingTo("0");
        assertThat(t.hasPeopleDebt()).isFalse();
        assertThat(t.rule().situation()).isEqualTo(SavingsRules.BANK_LOAN_COMFORTABLE);
        assertThat(t.loans()).singleElement().satisfies(x -> {
            assertThat(x.type()).isEqualTo("MONTHLY");
            assertThat(x.left()).isEqualByComparingTo("4500000");
            assertThat(x.ask()).isEqualByComparingTo("500000");
            assertThat(x.note()).isEqualTo(OverviewService.NOTE_SMALL_MONTHLY);
        });
    }

    @Test
    void aLargeMonthlyLoanCounts() {
        fx.f.borrowed(9, "Ota-onam", "9000000", "0", "1000000", lastMonth, now.minusMonths(1).atDay(1));
        OverviewService.RuleTrace t = consistent();
        assertThat(t.smallMonthlyIgnored()).isFalse();
        assertThat(t.peopleDebtForRule()).isEqualByComparingTo("1000000");
        assertThat(t.loans().getFirst().note()).isNull();
    }

    @Test
    void notStartedAndNasiyaLaterAreListedWithNoAsk() {
        fx.f.borrowed(9, "Ota-onam", "4500000", "0", "500000", lastMonth, now.plusMonths(1).atDay(1));
        LoanTaken n = fx.f.borrowed(14, "Uzum Nasiya", "6300000", "0", null, lastMonth, null);
        n.setNasiya(true);
        n.setPrincipalAmount(new BigDecimal("6000000"));
        n.setDueDate(now.plusMonths(1).atDay(5));
        OverviewService.RuleTrace t = consistent();
        assertThat(t.rule().situation()).isEqualTo(SavingsRules.NO_DEBT);
        assertThat(t.loans()).extracting(OverviewService.TraceLoan::note)
                .containsExactly(OverviewService.NOTE_NASIYA_LATER, OverviewService.NOTE_NOT_STARTED);
        assertThat(t.loans().getFirst().type()).isEqualTo("NASIYA");
        assertThat(t.loans().getFirst().dueDate()).isEqualTo(now.plusMonths(1).atDay(5));
        RecalcResponse.LoanLine line = recalc.preview(today).getRecalculated().getWhy().getLoans().getFirst();
        assertThat(line.getDueDate()).isEqualTo(now.plusMonths(1).atDay(5).toString());
    }

    @Test
    void moneyBorrowedAfterTheFirstIsAfterCutInTheCurrentBlockAndCountedToday() {
        bank("4000000");
        assertThat(recorder.fix(today)).isTrue();
        asap(7, today, "1000000");

        RecalcResponse r = recalc.preview(today);
        RecalcResponse.Why cur = r.getCurrent().getWhy();
        assertThat(cur.getBasis()).isEqualTo(RuleRecalcService.BASIS_MONTH_START);
        assertThat(cur.getAsOf()).isEqualTo(now.atDay(1).toString());
        assertThat(cur.isExact()).isTrue();
        assertThat(cur.getLoans()).singleElement().satisfies(x -> {
            assertThat(x.getNote()).isEqualTo(OverviewService.NOTE_AFTER_CUT);
            assertThat(x.getAsk()).isEqualByComparingTo("0");
            assertThat(x.getLeft()).isEqualByComparingTo("1000000");
        });
        assertThat(cur.getAsksTotal()).isEqualByComparingTo("0");
        RecalcResponse.Why tod = r.getRecalculated().getWhy();
        assertThat(tod.getLoans()).singleElement().satisfies(x -> assertThat(x.getNote()).isNull());
        assertThat(tod.getAsksTotal()).isEqualByComparingTo("1000000");
    }

    @Test
    void afterARecalculationTheCurrentBlockUsesItsDay() {
        LoanTaken l = asap(7, lastMonth, "1000000");
        assertThat(recorder.fix(today)).isTrue();
        fx.f.ledger.add(today, TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "1000000")
                .setRepaidLoanTakenId(l.getId());
        l.setPaidAmount(l.getTotalAmount());
        l.setStatus(RecordStatus.PAID);

        RecalcResponse before = recalc.preview(today);
        // At the 1st the loan was still owed in full.
        assertThat(before.getCurrent().getWhy().getLoans()).singleElement()
                .satisfies(x -> assertThat(x.getLeft()).isEqualByComparingTo("1000000"));

        RecalcResponse after = recalc.apply(today);
        RecalcResponse.Why cur = after.getCurrent().getWhy();
        assertThat(cur.getBasis()).isEqualTo(RuleRecalcService.BASIS_RECALCULATED);
        assertThat(cur.getAsOf()).isEqualTo(today.toString());
        assertThat(cur.isExact()).isTrue();
        assertThat(cur.getLoans()).isEmpty();
        assertThat(after.getCurrent().getSituation()).isEqualTo(SavingsRules.NO_DEBT);
    }

    @Test
    void exactIsFalseWhenTheDataChangedSince() {
        asap(7, lastMonth, "1000000");
        assertThat(recorder.fix(today)).isTrue();
        assertThat(stored.getFirst().getSituation()).isEqualTo(SavingsRules.DEBTS_COMFORTABLE);
        fx.f.loansTaken.clear();   // the loan was deleted since the 1st

        RecalcResponse.Why cur = recalc.preview(today).getCurrent().getWhy();
        assertThat(cur.isExact()).isFalse();
        assertThat(cur.getLoans()).isEmpty();
    }
}
