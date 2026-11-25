package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uz.tracker.trackerproject.dto.request.CustomSavingRequest;
import uz.tracker.trackerproject.dto.response.ProfileResponse;
import uz.tracker.trackerproject.dto.response.RecalcResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MonthRule;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.MonthRuleRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The owner's "Recalculate" button (RECALC-SPEC, Batch 76): this month's rule — fixed on its 1st
 * (Batch 72) — is re-decided from today's data only when the owner asks, and only for the month under way.
 */
class RuleRecalcTest {

    private final YearMonth now = OverviewService.ownerMonth();
    private final LocalDate today = LocalDate.now(LevelScheduler.OWNER_ZONE);
    private LevelsFixture fx;
    private final List<MonthRule> stored = new ArrayList<>();
    private final AtomicInteger saves = new AtomicInteger();
    private MonthRuleRecorder recorder;
    private RuleRecalcService recalc;
    private long nextId = 900;

    @BeforeEach
    void setUp() {
        fx = new LevelsFixture();
        MonthRuleRepository repo = mock(MonthRuleRepository.class);
        when(repo.findByMonth(any())).thenAnswer(i -> stored.stream()
                .filter(r -> r.getMonth().equals(i.getArgument(0))).findFirst());
        when(repo.findAll()).thenAnswer(i -> List.copyOf(stored));
        when(repo.save(any(MonthRule.class))).thenAnswer(i -> {
            MonthRule r = i.getArgument(0);
            saves.incrementAndGet();
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

    private void bankLoan() {
        fx.f.bankLoan(1, "Bank", "Car", "48000000", "4000000", now.minusMonths(6).atDay(5), now.plusMonths(12).atDay(5));
    }

    private LoanTaken borrowedAsap(LocalDate on, String amount) {
        return fx.f.borrowed(7, "Ali", amount, "0", null, on, null);
    }

    /** Repays {@code l} in full today. */
    private void repayToday(LoanTaken l) {
        fx.f.ledger.add(today, TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, l.getTotalAmount().toPlainString())
                .setRepaidLoanTakenId(l.getId());
        l.setPaidAmount(l.getTotalAmount());
        l.setStatus(RecordStatus.PAID);
    }

    @Test
    void theFirstAsTheCutIsTheRuleAtStart() {
        bankLoan();
        borrowedAsap(now.minusMonths(1).atDay(20), "1000000");
        assertThat(fx.f.overview.ruleAsOf(now, now.atDay(1))).isEqualTo(fx.f.overview.ruleAtStart(now));
        assertThat(fx.f.overview.ruleAtStart(now).situation()).isEqualTo(SavingsRules.BANK_AND_DEBTS);
    }

    @Test
    void moneyBorrowedMidMonthCountsNowButNotAtTheStart() {
        bankLoan();
        borrowedAsap(today, "1000000");
        assertThat(fx.f.overview.ruleAtStart(now).situation()).isEqualTo(SavingsRules.BANK_LOAN_TIGHT);
        assertThat(fx.f.overview.ruleNow(now, today).situation()).isEqualTo(SavingsRules.BANK_AND_DEBTS);
    }

    @Test
    void moneyRepaidInFullMidMonthCountsAtTheStartButNotNow() {
        LoanTaken l = borrowedAsap(now.minusMonths(1).atDay(20), "1000000");
        repayToday(l);
        assertThat(fx.f.overview.ruleAtStart(now).situation()).isNotEqualTo(SavingsRules.NO_DEBT);
        assertThat(fx.f.overview.ruleNow(now, today).situation()).isEqualTo(SavingsRules.NO_DEBT);
    }

    @Test
    void previewShowsBothRulesWithTheirPercentages() {
        LoanTaken l = borrowedAsap(now.minusMonths(1).atDay(20), "1000000");
        assertThat(recorder.fix(today)).isTrue();
        repayToday(l);

        RecalcResponse r = recalc.preview(today);
        assertThat(r.getMonth()).isEqualTo(now.toString());
        assertThat(r.isChanged()).isTrue();
        assertThat(r.getRecalculatedAt()).isNull();
        assertThat(r.getCurrent().getSituation()).isEqualTo(stored.getFirst().getSituation());
        assertThat(r.getRecalculated().getLevel()).isEqualTo(1);
        assertThat(r.getRecalculated().getSituation()).isEqualTo(SavingsRules.NO_DEBT);
        assertThat(r.getRecalculated().getPercents().getDonation()).isEqualByComparingTo("10");
        assertThat(r.getRecalculated().getPercents().getEmergency()).isEqualByComparingTo("5");
        assertThat(r.getRecalculated().getPercents().getInvestments()).isEqualByComparingTo("15");
        assertThat(r.getRecalculated().getTotal()).isEqualByComparingTo("30");
        assertThat(r.getRecalculated().getOwn()).isEmpty();
        // Each block carries its level version's tight / comfortable line.
        BigDecimal cutoff = fx.f.overview.ruleBook().version(1, now).cutoff();
        assertThat(cutoff).isNotNull();
        assertThat(r.getCurrent().getCutoff()).isEqualByComparingTo(cutoff);
        assertThat(r.getRecalculated().getCutoff()).isEqualByComparingTo(cutoff);
        // A preview writes nothing.
        assertThat(stored).singleElement().satisfies(m -> assertThat(m.getRecalculatedAt()).isNull());
    }

    @Test
    void applyRewritesOnlyThisMonthAndTheRecorderLeavesItAlone() {
        MonthRule last = new MonthRule();
        last.setMonth(now.minusMonths(1).atDay(1));
        last.setLevel(1);
        last.setSituation(SavingsRules.BANK_AND_DEBTS);
        stored.add(last);
        LoanTaken l = borrowedAsap(now.minusMonths(1).atDay(20), "1000000");
        assertThat(recorder.fix(today)).isTrue();
        String before = stored.get(1).getSituation();
        repayToday(l);

        RecalcResponse r = recalc.apply(today);
        assertThat(r.isChanged()).isFalse();
        assertThat(r.getCurrent().getSituation()).isEqualTo(SavingsRules.NO_DEBT);
        assertThat(r.getRecalculatedAt()).isNotNull();
        MonthRule mine = stored.get(1);
        assertThat(mine.getMonth()).isEqualTo(now.atDay(1));
        assertThat(mine.getSituation()).isEqualTo(SavingsRules.NO_DEBT).isNotEqualTo(before);
        assertThat(mine.getRecalculatedAt()).isNotNull();
        assertThat(last.getSituation()).isEqualTo(SavingsRules.BANK_AND_DEBTS);
        assertThat(last.getRecalculatedAt()).isNull();

        // The month now reads the recalculated rule, and the daily fix never rewrites it.
        assertThat(fx.f.overview.fixedRule(now).situation()).isEqualTo(SavingsRules.NO_DEBT);
        assertThat(recorder.fix(today)).isFalse();
        assertThat(mine.getSituation()).isEqualTo(SavingsRules.NO_DEBT);
        // Profile says when.
        ProfileResponse p = new ProfileService(fx.f.overview, mock(TransactionRepository.class)).profile(today, "owner");
        assertThat(p.getRuleRecalculatedAt()).isEqualTo(r.getRecalculatedAt());
        // A second press with nothing new changes nothing — the time stays.
        RecalcResponse again = recalc.preview(today);
        assertThat(again.isChanged()).isFalse();
        assertThat(again.getRecalculatedAt()).isEqualTo(r.getRecalculatedAt());
    }

    @Test
    void applyWithNothingChangedWritesNothing() {
        bankLoan();
        assertThat(recorder.fix(today)).isTrue();
        int before = saves.get();

        RecalcResponse r = recalc.apply(today);
        assertThat(r.isChanged()).isFalse();
        assertThat(r.getRecalculatedAt()).isNull();
        assertThat(saves.get()).isEqualTo(before);
        assertThat(stored).singleElement().satisfies(m -> assertThat(m.getRecalculatedAt()).isNull());
    }

    @Test
    void applyBeforeTheMonthIsStoredStoresIt() {
        LoanTaken l = borrowedAsap(now.minusMonths(1).atDay(20), "1000000");
        repayToday(l);
        assertThat(stored).isEmpty();

        recalc.apply(today);
        assertThat(stored).singleElement().satisfies(m -> {
            assertThat(m.getMonth()).isEqualTo(now.atDay(1));
            assertThat(m.getSituation()).isEqualTo(SavingsRules.NO_DEBT);
            assertThat(m.getRecalculatedAt()).isNotNull();
        });
    }

    @Test
    void withoutAnIncomeItRefuses() {
        fx.income.clear();
        fx.f.settings.setMonthlyStableIncome(null);
        assertThatThrownBy(() -> recalc.preview(today))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(RuleRecalcService.NO_INCOME);
        assertThatThrownBy(() -> recalc.apply(today))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(RuleRecalcService.NO_INCOME);
        assertThat(stored).isEmpty();
    }

    @Test
    void onlyTheMonthUnderWay() {
        assertThatThrownBy(() -> recalc.apply(now.minusMonths(1).atDay(15)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(RuleRecalcService.NOT_THIS_MONTH);
        assertThatThrownBy(() -> recalc.preview(now.plusMonths(1).atDay(2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(RuleRecalcService.NOT_THIS_MONTH);
    }

    @Test
    void ownSavingsAreListedInBothBlocksWithTheirPercent() {
        InvestmentRepository pots = mock(InvestmentRepository.class);
        when(pots.save(any(Investment.class))).thenAnswer(i -> {
            Investment s = i.getArgument(0);
            if (s.getId() == null) s.setId(nextId++);
            if (!fx.f.holdings.contains(s)) fx.f.holdings.add(s);
            return s;
        });
        when(pots.findById(any())).thenAnswer(i -> fx.f.holdings.stream()
                .filter(s -> s.getId().equals(i.getArgument(0))).findFirst());
        ReflectionTestUtils.setField(fx.levels, "investmentRepository", pots);
        ReflectionTestUtils.setField(fx.levels, "transactionRepository", mock(TransactionRepository.class));
        CustomSavingRequest car = new CustomSavingRequest();
        car.setName("Car");
        car.setPercent(new BigDecimal("3"));
        car.setFrom(now.toString());
        fx.levels.createSaving(car, today);

        LoanTaken l = borrowedAsap(now.minusMonths(1).atDay(20), "1000000");
        repayToday(l);
        RecalcResponse r = recalc.preview(today);
        for (RecalcResponse.Rule rule : List.of(r.getCurrent(), r.getRecalculated())) {
            assertThat(rule.getOwn()).singleElement().satisfies(o -> {
                assertThat(o.getName()).isEqualTo("Car");
                assertThat(o.getPercent()).isEqualByComparingTo("3");
            });
        }
        assertThat(r.getRecalculated().getTotal()).isEqualByComparingTo("33");
    }
}
