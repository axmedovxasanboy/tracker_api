package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Upcoming;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RepaymentType;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A nasiya (Uzum Nasiya, Batch 75): borrowed for a fixed term and owed in full — principal plus its
 * fee — by the deadline. The Plan asks all of what is left from the deadline's month on (never the
 * ASAP 34% / 70% rule, which would let the deadline pass), the daily walk dates it on the deadline,
 * and the rule on the 1st counts it as money owed. The owner's case: 6,000,000 + 5% = 6,300,000 on an
 * 8,000,000 salary, where an ordinary ASAP loan would ask only 34%.
 */
class NasiyaTest {

    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final YearMonth DEC = YearMonth.of(2026, 12);
    private static final YearMonth JAN = YearMonth.of(2027, 1);
    private static final LocalDate BORROWED = LocalDate.of(2026, 11, 20);
    private static final LocalDate DEADLINE = LocalDate.of(2026, 12, 20);
    private static final long ID = 30;

    private AnalyticsFixture f;

    @BeforeEach
    void setUp() {
        f = new AnalyticsFixture();
        f.wallet("CARD", Currency.UZS, "9000000");
        f.stableIncome("8000000", LocalDate.of(2026, 9, 1));
    }

    private LoanTaken nasiya(AnalyticsFixture fx, long id, LocalDate borrowed, LocalDate due) {
        LoanTaken l = fx.borrowed(id, "Uzum Nasiya", "6300000", "0", null, borrowed, null);
        l.setNasiya(true);
        l.setPrincipalAmount(new BigDecimal("6000000"));
        l.setDueDate(due);
        l.setRepaymentType(RepaymentType.ASAP);
        return l;
    }

    private OverviewService.DebtAsk ask(YearMonth month, LocalDate asOf, long id) {
        return f.overview.debtAsks(month, asOf).stream().filter(a -> a.id() == id).findFirst().orElseThrow();
    }

    private List<Upcoming> upcoming(LocalDate today, long id) {
        return f.advisor.advise(today).getDaily().getUpcoming().stream()
                .filter(u -> "LOAN".equals(u.getKind()) && u.getRefId() == id).toList();
    }

    // ── The month's ask ───────────────────────────────────────────────────────

    @Test
    void nothingBeforeTheDeadlinesMonth_thenEverythingLeft_neverThe34Percent() {
        nasiya(f, ID, BORROWED, DEADLINE);
        LoanTaken plain = f.borrowed(31, "Akam", "6300000", "0", null, BORROWED, null);

        assertThat(ask(NOV, NOV.atEndOfMonth(), ID).ask()).isEqualByComparingTo("0");
        assertThat(ask(DEC, DEC.atEndOfMonth(), ID).ask()).isEqualByComparingTo("6300000");
        assertThat(ask(DEC, DEC.atEndOfMonth(), ID).nasiya()).isTrue();
        assertThat(ask(DEC, DEC.atEndOfMonth(), ID).dueDate()).isEqualTo(DEADLINE);
        // Past the deadline, not switched: still all of it.
        assertThat(ask(JAN, JAN.atEndOfMonth(), ID).ask()).isEqualByComparingTo("6300000");

        // An ordinary ASAP loan of the same size is unchanged: 6.3M is above 70% of 8M → 34%.
        assertThat(ask(NOV, NOV.atEndOfMonth(), plain.getId()).ask()).isEqualByComparingTo("2142000");
        assertThat(ask(NOV, NOV.atEndOfMonth(), plain.getId()).nasiya()).isFalse();
        assertThat(ask(NOV, NOV.atEndOfMonth(), plain.getId()).dueDate()).isNull();
    }

    @Test
    void aRepaymentInTheDeadlinesMonthComesOffWhatIsStillDue() {
        LoanTaken l = nasiya(f, ID, BORROWED, DEADLINE);
        f.ledger.add(DEC.atDay(5), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "1000000")
                .setRepaidLoanTakenId(ID);
        l.setPaidAmount(new BigDecimal("1000000"));

        OverviewService.DebtAsk a = ask(DEC, DEC.atDay(10), ID);
        assertThat(a.ask()).isEqualByComparingTo("6300000");
        assertThat(a.repaid()).isEqualByComparingTo("1000000");
        assertThat(a.due()).isEqualByComparingTo("5300000");
        assertThat(a.left()).isEqualByComparingTo("5300000");
    }

    @Test
    void theSharedHelper() {
        assertThat(OverviewService.nasiyaAsk(new BigDecimal("6300000"), DEADLINE, NOV)).isEqualByComparingTo("0");
        assertThat(OverviewService.nasiyaAsk(new BigDecimal("6300000"), DEADLINE, DEC)).isEqualByComparingTo("6300000");
        assertThat(OverviewService.nasiyaAsk(new BigDecimal("6300000"), DEADLINE, JAN)).isEqualByComparingTo("6300000");
        assertThat(OverviewService.nasiyaAsk(BigDecimal.ZERO, DEADLINE, DEC)).isEqualByComparingTo("0");
    }

    // ── The daily walk ────────────────────────────────────────────────────────

    @Test
    void theWalkDatesItOnTheDeadline() {
        nasiya(f, ID, BORROWED, DEADLINE);
        assertThat(upcoming(LocalDate.of(2026, 12, 10), ID)).singleElement().satisfies(u -> {
            assertThat(u.getDate()).isEqualTo(DEADLINE);
            assertThat(u.getAmount()).isEqualByComparingTo("6300000");
            assertThat(u.isOverdue()).isFalse();
        });
    }

    @Test
    void aDeadlineNextMonthIsOnItsDay_withNothingBefore() {
        nasiya(f, ID, BORROWED, DEADLINE);
        assertThat(upcoming(LocalDate.of(2026, 11, 25), ID)).singleElement().satisfies(u -> {
            assertThat(u.getDate()).isEqualTo(DEADLINE);
            assertThat(u.getAmount()).isEqualByComparingTo("6300000");
            assertThat(u.isOverdue()).isFalse();
        });
    }

    @Test
    void pastTheDeadlineItIsOverdueToday() {
        nasiya(f, ID, BORROWED, DEADLINE);
        LocalDate today = LocalDate.of(2026, 12, 22);
        assertThat(upcoming(today, ID)).singleElement().satisfies(u -> {
            assertThat(u.getDate()).isEqualTo(today);
            assertThat(u.getAmount()).isEqualByComparingTo("6300000");
            assertThat(u.isOverdue()).isTrue();
        });
    }

    @Test
    void switchedToInstallmentsItIsAnOrdinaryMonthlyLoan() {
        LoanTaken l = nasiya(f, ID, BORROWED, DEADLINE);
        l.setTotalAmount(new BigDecimal("9066000"));
        l.setRepaymentType(RepaymentType.MONTHLY);
        l.setPlannedMonthlyPayment(new BigDecimal("755500"));
        l.setPaymentStartDate(LocalDate.of(2027, 1, 15));
        l.setInstallmentsSince(LocalDate.of(2026, 12, 22));

        assertThat(ask(JAN, JAN.atEndOfMonth(), ID).ask()).isEqualByComparingTo("755500");
        assertThat(ask(JAN, JAN.atEndOfMonth(), ID).nasiya()).isFalse();
        assertThat(ask(DEC, DEC.atEndOfMonth(), ID).ask()).isEqualByComparingTo("0");
        assertThat(upcoming(LocalDate.of(2026, 12, 23), ID)).allSatisfy(u -> {
            assertThat(u.getDate()).isEqualTo(LocalDate.of(2027, 1, 15));
            assertThat(u.getAmount()).isEqualByComparingTo("755500");
        });
    }

    // ── The rule on the 1st ───────────────────────────────────────────────────

    private LevelsFixture ruleFixture() {
        LevelsFixture fx = new LevelsFixture();
        YearMonth now = OverviewService.ownerMonth();
        fx.incomeFrom(now.minusMonths(3), "8000000");
        fx.f.bankLoan(1, "Bank", "Car", "48000000", "4000000", now.minusMonths(6).atDay(5), now.plusMonths(12).atDay(5));
        return fx;
    }

    @Test
    void theRuleOnTheFirstCountsANasiyaDueThisMonthAsMoneyOwed() {
        YearMonth now = OverviewService.ownerMonth();
        LevelsFixture fx = ruleFixture();
        assertThat(fx.f.overview.ruleAtStart(now).situation()).isEqualTo(SavingsRules.BANK_LOAN_TIGHT);

        LoanTaken l = nasiya(fx.f, ID, now.minusMonths(1).atDay(20), now.atDay(20));
        l.setTotalAmount(new BigDecimal("1050000"));
        l.setPrincipalAmount(new BigDecimal("1000000"));
        assertThat(fx.f.overview.ruleAtStart(now).situation()).isEqualTo(SavingsRules.BANK_AND_DEBTS);
    }

    @Test
    void aNasiyaDueNextMonthAsksNothingThisMonth_soTheRuleIsAsWithoutIt() {
        YearMonth now = OverviewService.ownerMonth();
        LevelsFixture fx = ruleFixture();
        LoanTaken l = nasiya(fx.f, ID, now.minusMonths(1).atDay(20), now.plusMonths(1).atDay(20));
        l.setTotalAmount(new BigDecimal("1050000"));
        l.setPrincipalAmount(new BigDecimal("1000000"));
        assertThat(fx.f.overview.ruleAtStart(now).situation()).isEqualTo(SavingsRules.BANK_LOAN_TIGHT);
        assertThat(fx.f.overview.ruleAtStart(now.plusMonths(1)).situation()).isEqualTo(SavingsRules.BANK_AND_DEBTS);
    }
}
