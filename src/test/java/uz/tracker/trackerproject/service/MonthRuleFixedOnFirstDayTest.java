package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MonthRule;
import uz.tracker.trackerproject.enums.Currency;
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
 * The owner's rule (2026-10-15): a month's savings rule — the level and the situation behind
 * "Level 1 · Bank loan — under 5.000.000 UZS left" — is decided on the month's first day and never
 * changes. Money borrowed mid-month shows from the next month; money owed on the 1st keeps counting
 * even once repaid. The owner's case: 8M income, a 4M bank installment, 1M borrowed ASAP mid-month.
 */
class MonthRuleFixedOnFirstDayTest {

    private static final String BANK_ONLY_TIGHT = "1.2.1.tight";
    private static final String BANK_AND_PEOPLE = "1.2.3";

    private final YearMonth now = OverviewService.ownerMonth();
    private LevelsFixture fx;
    private final List<MonthRule> stored = new ArrayList<>();
    private MonthRuleRecorder recorder;

    @BeforeEach
    void setUp() {
        fx = new LevelsFixture();
        MonthRuleRepository repo = mock(MonthRuleRepository.class);
        when(repo.findByMonth(any())).thenAnswer(i -> stored.stream()
                .filter(r -> r.getMonth().equals(i.getArgument(0))).findFirst());
        when(repo.findAll()).thenAnswer(i -> List.copyOf(stored));
        when(repo.save(any(MonthRule.class))).thenAnswer(i -> {
            MonthRule r = i.getArgument(0);
            r.setId((long) stored.size() + 1);
            stored.add(r);
            return r;
        });
        ReflectionTestUtils.setField(fx.f.overview, "monthRuleRepository", repo);
        recorder = new MonthRuleRecorder(fx.f.overview, repo);
        ReflectionTestUtils.setField(fx.levels, "monthRules", recorder);

        fx.incomeFrom(now.minusMonths(3), "8000000");
        fx.f.bankLoan(1, "Bank", "Car", "48000000", "4000000", now.minusMonths(6).atDay(5), now.plusMonths(12).atDay(5));
    }

    private String rule(YearMonth month) {
        return fx.f.overview.getTierIgnoringSubscriptions(month, Currency.UZS, month.atDay(15))
                .getAllocation().getScenarioKey();
    }

    private LoanTaken borrowedAsap(LocalDate on, String amount) {
        return fx.f.borrowed(7, "Ali", amount, "0", null, on, null);
    }

    @Test
    void moneyBorrowedMidMonthWaitsForTheNextFirst() {
        borrowedAsap(now.atDay(2), "1000000");

        // Not stored yet: the month under way is still read as its first day decided it.
        assertThat(rule(now)).isEqualTo(BANK_ONLY_TIGHT);
        fx.levels.refreshQuietly(LocalDate.now(LevelScheduler.OWNER_ZONE));
        assertThat(stored).singleElement().satisfies(r -> {
            assertThat(r.getMonth()).isEqualTo(now.atDay(1));
            assertThat(r.getLevel()).isEqualTo(1);
            assertThat(r.getSituation()).isEqualTo(SavingsRules.BANK_LOAN_TIGHT);
        });
        assertThat(rule(now)).isEqualTo(BANK_ONLY_TIGHT);
        // Next month's first day will count it — what Profile's "From {month}" line previews.
        assertThat(rule(now.plusMonths(1))).isEqualTo(BANK_AND_PEOPLE);
    }

    @Test
    void theStoredRuleNeverMoves() {
        assertThat(recorder.fix(now.atDay(1))).isTrue();

        // Everything that would move it, after the 1st: another loan, a smaller installment, a raise.
        borrowedAsap(now.atDay(9), "1000000");
        fx.f.bankLoans.getFirst().setMonthlyPayment(new BigDecimal("1000000"));
        fx.incomeFrom(now, "20000000");

        assertThat(recorder.fix(now.atDay(20))).isFalse();
        assertThat(stored).hasSize(1);
        assertThat(rule(now)).isEqualTo(BANK_ONLY_TIGHT);
        assertThat(fx.level(now)).isEqualTo(1);
        // The ledger (carry-over, Home's amounts) asks the same percentages: emergency 2%, not 0%.
        AllocationLedgerResponse ledger = fx.f.overview.getAllocationLedger(now, Currency.UZS);
        assertThat(ledger.getBuckets()).filteredOn(b -> "EMERGENCY".equals(b.getBucket()))
                .singleElement().satisfies(b -> assertThat(b.getPercent()).isEqualByComparingTo("2"));
    }

    @Test
    void moneyOwedOnTheFirstKeepsCountingOnceRepaid() {
        LoanTaken l = borrowedAsap(now.minusMonths(1).atDay(20), "1000000");
        fx.f.ledger.add(now.atDay(3), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "1000000")
                .setRepaidLoanTakenId(l.getId());
        l.setPaidAmount(new BigDecimal("1000000"));
        l.setStatus(RecordStatus.PAID);

        assertThat(fx.f.overview.ruleAtStart(now).situation()).isEqualTo(SavingsRules.BANK_AND_DEBTS);
        assertThat(rule(now)).isEqualTo(BANK_AND_PEOPLE);
        // Cleared before next month begins: next month is back to the bank loan alone.
        assertThat(rule(now.plusMonths(1))).isEqualTo(BANK_ONLY_TIGHT);
    }

    @Test
    void aMonthWithoutAnIncomeIsFixedOnceTheIncomeIsSet() {
        fx.income.clear();
        fx.f.settings.setMonthlyStableIncome(null);
        assertThat(recorder.fix(now.atDay(1))).isFalse();
        assertThat(stored).isEmpty();

        fx.incomeFrom(now, "8000000");
        assertThat(recorder.fix(now.atDay(4))).isTrue();
        assertThat(stored).singleElement().satisfies(r -> assertThat(r.getSituation()).isEqualTo(SavingsRules.BANK_LOAN_TIGHT));
    }

    @Test
    void anEarlierMonthWithoutARowIsWorkedOutAsBefore() {
        YearMonth last = now.minusMonths(1);
        borrowedAsap(last.atDay(10), "1000000");
        // Before the rule existed months were read from today's loans — borrowed in it, so it counts.
        assertThat(rule(last)).isEqualTo(BANK_AND_PEOPLE);
    }
}
