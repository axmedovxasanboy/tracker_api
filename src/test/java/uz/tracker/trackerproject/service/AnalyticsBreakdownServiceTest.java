package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.Expected;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.Goal;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.GoalMonth;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.Holding;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.Line;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.MonthFlow;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Flow;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.HoldingMonthSnapshot;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.enums.TransactionFlow;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static uz.tracker.trackerproject.service.OwnerLiveFixture.OCT;
import static uz.tracker.trackerproject.service.OwnerLiveFixture.SEP;
import static uz.tracker.trackerproject.service.OwnerLiveFixture.TODAY;
import static uz.tracker.trackerproject.service.OwnerLiveFixture.sep;

/**
 * GET /analytics/breakdown on the owner's live data of 2 October 2026 (OwnerLiveFixture): every
 * number of ANALYTICS-V2-SPEC §6.1 — September, October so far, 12 months, and the same after Q2
 * (the 7 Sep salary marked as August's) — and the rules of §1.2–§4.4 one by one.
 */
class AnalyticsBreakdownServiceTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static Line line(List<Line> lines, String key) {
        return lines.stream().filter(l -> l.getKey().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no line " + key + " in " + lines.stream().map(Line::getKey).toList()));
    }

    private static Line child(Line parent, String key) {
        return line(parent.getChildren(), key);
    }

    private static Goal goal(AnalyticsBreakdownResponse r, long id) {
        return r.getGoals().stream().filter(g -> g.getRefId() == id).findFirst().orElseThrow();
    }

    private static GoalMonth month(Goal g, YearMonth m) {
        return g.getMonths().stream().filter(x -> x.getMonth().equals(m.toString())).findFirst().orElseThrow();
    }

    /** The live data with the 7 Sep salary marked as August's pay (Q2 answered "August"). */
    private static OwnerLiveFixture afterQ2() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        f.salary7Sep.setSalaryMonth(LocalDate.of(2026, 8, 1));
        return f;
    }

    private static BigDecimal sumAmounts(List<Line> lines) {
        return lines.stream().map(Line::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── §6.1 September 2026 (no base month) ──────────────────────────────────

    @Test
    void september_inIs41_204_000_andItsSalaryGroupIsProfilesPayForSeptember() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);
        MonthFlow m = r.getMonths().get(0);

        assertThat(r.getMonths()).hasSize(1);
        assertThat(m.getMonth()).isEqualTo("2026-09");
        assertThat(m.isComplete()).isTrue();
        assertThat(m.getEarned()).isEqualByComparingTo("41204000");
        assertThat(m.getEarnedPay()).isEqualByComparingTo("15059000");
        assertThat(m.getEarnedBonus()).isEqualByComparingTo("26045000");
        assertThat(m.getEarnedOther()).isEqualByComparingTo("100000");

        Line salaryGroup = line(r.getIncome(), "cat:1");
        assertThat(salaryGroup.getAmount()).isEqualByComparingTo("41104000");    // = Profile "Pay for September"
        assertThat(salaryGroup.getIncomeKind()).isNull();                         // its children differ
        assertThat(salaryGroup.getNameUz()).isEqualTo("Oylik maosh");
        assertThat(salaryGroup.getChildren()).extracting("key", "amount", "incomeKind", "nameUz").containsExactly(
                tuple("cat:23", n("26045000"), "BONUS", "Premiya"),
                tuple("cat:24", n("13059000"), "PAY", "Maosh"),
                tuple("cat:26", n("2000000"), "PAY", "Avans"));
        assertThat(r.getIncome()).extracting("key", "amount").containsExactly(
                tuple("cat:1", n("41104000")), tuple("cat:6", n("100000")));
        assertThat(line(r.getIncome(), "cat:6").getIncomeKind()).isEqualTo("OTHER");
    }

    @Test
    void september_theSalaryPaidOn2OctoberIsListedAsReceivedThenOnItsRowAndOnTheInRow() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);

        assertThat(child(line(r.getIncome(), "cat:1"), "cat:24").getOtherMonth())
                .extracting("date", "amount").containsExactly(tuple(LocalDate.of(2026, 10, 2), n("7170000")));
        // Totals' In row sums the income[] lines' otherMonth: one date → "7,2 M of it received 2 Oct".
        assertThat(r.getIncome().stream().flatMap(l -> l.getOtherMonth().stream()).toList())
                .extracting("date", "amount").containsExactly(tuple(LocalDate.of(2026, 10, 2), n("7170000")));
        assertThat(r.getStableIncome()).isEqualByComparingTo("8000000");
        assertThat(r.getReceivedForOtherMonths()).isEmpty();
    }

    @Test
    void september_outIs22_291_000_everydayBillsAndLoans() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);
        MonthFlow m = r.getMonths().get(0);

        assertThat(m.getOut()).isEqualByComparingTo("22291000");
        assertThat(m.getEveryday()).isEqualByComparingTo("15036000");
        assertThat(m.getEverydayUnitemised()).isEqualByComparingTo("4048000");
        assertThat(m.getBills()).isEqualByComparingTo("5300000");
        assertThat(m.getLoanPayments()).isEqualByComparingTo("1955000");
        // Everyday a day: everyday ÷ days.
        assertThat(m.getDays()).isEqualTo(30);
        assertThat(m.getEveryday().divide(BigDecimal.valueOf(m.getDays()))).isEqualByComparingTo("501200");
    }

    @Test
    void september_theOutRowsByAmountAddUpToOutExactly() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);

        assertThat(r.getOut()).extracting("key", "amount").containsExactly(
                tuple("cat:9", n("7588000")),
                tuple("unitemised", n("4048000")),
                tuple("cat:25", n("3950000")),
                tuple("loans", n("1955000")),
                tuple("cat:7", n("1662000")),
                tuple("cat:31", n("1350000")),
                tuple("cat:13", n("1100000")),
                tuple("cat:27", n("398000")),
                tuple("cat:11", n("200000")),
                tuple("cat:28", n("40000")));
        assertThat(sumAmounts(r.getOut())).isEqualByComparingTo("22291000");

        Line housing = line(r.getOut(), "cat:9");
        assertThat(housing.getEveryday()).isEqualByComparingTo("3388000");
        assertThat(housing.getBills()).isEqualByComparingTo("4200000");          // "incl. 4,2 M bills"
        assertThat(housing.getChildren()).extracting("key", "kind", "name", "amount")
                .containsExactly(tuple("bill:3", "BILL", "Kvartira Arenda", n("4200000"))); // no sub-categories, no :self
        assertThat(housing.getTop()).extracting("description", "date", "amount").containsExactly(
                tuple("Perfectum WiFi", sep(19), n("2100000")),
                tuple("Toilet repair", sep(9), n("455000")),
                tuple("Xarajat", sep(12), n("394000")));                          // the rent bill is not in top

        Line education = line(r.getOut(), "cat:13");
        assertThat(education.getEveryday()).isEqualByComparingTo("0");           // "· bill"
        assertThat(education.getBills()).isEqualByComparingTo("1100000");
        assertThat(education.getChildren()).extracting("key", "name")
                .containsExactly(tuple("bill:4", "Noon Academy (Tabassum)"));
        assertThat(education.getTop()).isEmpty();

        assertThat(line(r.getOut(), "loans").getChildren()).extracting("key", "loanKind", "name", "amount", "closed")
                .containsExactly(tuple("loan:LOAN:5", "LOAN", "Uzum Bank", n("1155000"), false),
                        tuple("loan:LOAN:6", "LOAN", "Uzum Nasiya", n("800000"), false));
        // Smaller (2): Entertainment + Other.
        assertThat(line(r.getOut(), "cat:11").getAmount().add(line(r.getOut(), "cat:28").getAmount()))
                .isEqualByComparingTo("240000");
    }

    @Test
    void september_notItemisedIsTheWalletChecksByDayNetOfTheCorrection() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);

        Line unitemised = line(r.getOut(), "unitemised");
        assertThat(unitemised.getKind()).isEqualTo("NOT_ITEMISED");
        assertThat(unitemised.getRefId()).isEqualTo(21L);
        assertThat(unitemised.getChildren()).extracting("key", "kind", "amount").containsExactly(
                tuple("check:2026-09-15", "CHECK", n("1780000")),
                tuple("check:2026-09-23", "CHECK", n("1597000")),
                tuple("check:2026-09-28", "CHECK", n("671000")));
        // History's walletCheck=1 lists the 7 check rows and the correction: 4,048,000 in all.
        assertThat(unitemised.getCount()).isEqualTo(8);
        assertThat(unitemised.getHistory().getWalletCheck()).isTrue();
        assertThat(child(unitemised, "check:2026-09-15").getHistory().getFrom()).isEqualTo(sep(15));
    }

    @Test
    void september_setAsideIs4_520_000_andGoalsIsSentAtZeroWithItsSinceStart() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);
        MonthFlow m = r.getMonths().get(0);

        assertThat(m.getSaved()).isEqualByComparingTo("4520000");
        assertThat(r.getSetAside()).extracting("key", "amount", "sinceStart").containsExactly(
                tuple("group:INVESTMENTS", n("3000000"), n("3000000")),
                tuple("group:EMERGENCY", n("720000"), n("720000")),
                tuple("group:GOALS", n("0"), n("1000000")),
                tuple("group:DONATIONS", n("800000"), n("800000")));
        Line goals = line(r.getSetAside(), "group:GOALS");
        assertThat(goals.getExpected()).isNull();
        assertThat(goals.getChildren()).isEmpty();
        assertThat(line(r.getSetAside(), "group:INVESTMENTS").getChildren()).extracting("key", "name", "amount")
                .containsExactly(tuple("holding:2", "IMAN", n("3000000")));
        assertThat(line(r.getSetAside(), "group:EMERGENCY").getChildren()).extracting("key", "name", "amount")
                .containsExactly(tuple("holding:3", "IMAN (Emergency)", n("720000")));
        assertThat(line(r.getSetAside(), "group:DONATIONS").getChildren()).extracting("key", "kind", "name", "amount")
                .containsExactly(tuple("donation:30", "DONATION_KIND", "Mosque", n("800000")));
    }

    @Test
    void september_setAsideSinceTheStartRunsToTodayWhateverMonthIsOpen() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);

        assertThat(r.getSinceStart().getFrom()).isEqualTo("2026-09");
        assertThat(r.getSinceStart().getSetAside()).isEqualByComparingTo("5520000");
        assertThat(r.getSinceStart().getFromSavings()).isEqualByComparingTo("0");
        assertThat(r.getSetAside().stream().map(Line::getSinceStart).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("5520000");
        assertThat(r.getHoldingsNow().stream().map(Holding::getValue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("22029228");
    }

    @Test
    void september_leftOverIs14_393_000_andTheWalletsMovedByTheSalaryMonthRule() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);
        MonthFlow m = r.getMonths().get(0);

        assertThat(m.getLeftOver()).isEqualByComparingTo("14393000");
        assertThat(m.getPayForOtherMonths()).isEqualByComparingTo("-7170000");
        assertThat(m.getWalletChange()).isEqualByComparingTo("8878000");
    }

    @Test
    void september_notCountedAsInOrOutIsTheFourMovesWithNoExpected() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);

        assertThat(r.getMoved()).extracting("key", "flow", "amount", "expected").containsExactly(
                tuple("moved:BORROWED", TransactionFlow.BORROWED, n("1955000"), null),
                tuple("moved:LENT", TransactionFlow.LENT, n("1000000"), null),
                tuple("moved:RETURNED", TransactionFlow.RETURNED, n("700000"), null),
                tuple("moved:FROM_SAVINGS", TransactionFlow.FROM_SAVINGS, n("0"), null));
        assertThat(line(r.getMoved(), "moved:RETURNED").getChildren()).extracting("key", "kind", "name", "amount")
                .containsExactly(tuple("loanGiven:4", "PERSON", "Fozilbek", n("400000")),
                        tuple("loanGiven:3", "PERSON", "Mirjalol", n("300000")));
        assertThat(line(r.getMoved(), "moved:BORROWED").getChildren()).extracting("key", "name", "amount")
                .containsExactly(tuple("loanTaken:5", "Uzum Bank", n("1155000")),
                        tuple("loanTaken:6", "Uzum Nasiya", n("800000")));
        assertThat(line(r.getMoved(), "moved:BORROWED").getHistory().getFlow()).isEqualTo("BORROWED");
    }

    @Test
    void september_noBaseMeansNoExpected_andNoGoalsExistedYet() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);

        assertThat(r.getExpected()).isNull();
        assertThat(r.getAverage()).isNull();
        assertThat(r.getOut()).allSatisfy(l -> {
            assertThat(l.getExpected()).isNull();
            assertThat(l.getIsNew()).isFalse();
        });
        assertThat(r.getGoals()).isEmpty();                                       // "No goals in September."
        assertThat(r.getEverydayDaily()).hasSize(30);
        assertThat(r.getEverydayDaily().get(29).getCumulative()).isEqualByComparingTo("10988000"); // itemised only
    }

    @Test
    void september_theLinksToHistoryListExactlyTheLinesRows() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);

        assertThat(line(r.getIncome(), "cat:1").getHistory().getCategoryId()).isEqualTo(1L);
        assertThat(line(r.getOut(), "cat:9").getHistory().getCategoryId()).isEqualTo(9L);
        assertThat(child(line(r.getOut(), "cat:9"), "bill:3").getHistory())
                .extracting("flow", "categoryId").containsExactly("BILL", 9L);
        assertThat(line(r.getOut(), "loans").getHistory().getFlow()).isEqualTo("LOAN_PAYMENT");
        assertThat(child(line(r.getSetAside(), "group:INVESTMENTS"), "holding:2").getHistory().getInvestmentId())
                .isEqualTo(2L);
        // Two loans share flow=LOAN_PAYMENT: no filter lists exactly one of them.
        assertThat(child(line(r.getOut(), "loans"), "loan:LOAN:5").getHistory()).isNull();
    }

    @Test
    void september_getAnalyticsCountsTheSameMonthTheSameWay() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);
        MonthFlow m = r.getMonths().get(0);

        Flow old = f.analytics.analytics(SEP, SEP, TODAY).getTotals();
        assertThat(old.getEarned()).isEqualByComparingTo(m.getEarned());
        assertThat(old.getOut()).isEqualByComparingTo(m.getOut());
        assertThat(old.getSaved()).isEqualByComparingTo(m.getSaved());
        assertThat(old.getLeftOver()).isEqualByComparingTo(m.getLeftOver());
        assertThat(old.getWalletChange()).isEqualByComparingTo(m.getWalletChange());
    }

    // ── §6.1 October 2026 so far (base = September) ──────────────────────────

    @Test
    void october_theHistoryAndTheBaseAreSeptember() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);
        MonthFlow m = r.getMonths().get(0);
        Expected e = r.getExpected();

        assertThat(r.getHistory().getStart()).isEqualTo("2026-09");
        assertThat(r.getHistory().getFirstEarned()).isEqualTo("2026-09");
        assertThat(r.getHistory().getFirstOut()).isEqualTo("2026-09");
        assertThat(r.getHistory().getTrackingStart()).isEqualTo("2026-09");
        assertThat(r.getHistory().getTracked()).containsExactly("2026-09", "2026-10");
        // "Day 2 of 31 · Expected: average of 1 month (September)"
        assertThat(m.getDays()).isEqualTo(2);
        assertThat(m.getDaysInMonth()).isEqualTo(31);
        assertThat(m.isComplete()).isFalse();
        assertThat(m.isTracked()).isTrue();
        assertThat(e.getMonth()).isEqualTo("2026-10");
        assertThat(e.getBasedOn()).containsExactly("2026-09");
        assertThat(e.getSkipped()).isEmpty();
        assertThat(e.getDayFactor()).isEqualByComparingTo("1.0333");
        assertThat(r.getNotYetCount()).isZero();
    }

    @Test
    void october_soFar() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);
        MonthFlow m = r.getMonths().get(0);

        assertThat(m.getEarned()).isEqualByComparingTo("0");
        assertThat(m.getOut()).isEqualByComparingTo("400000");
        assertThat(m.getLoanPayments()).isEqualByComparingTo("400000");
        assertThat(m.getSaved()).isEqualByComparingTo("1000000");
        assertThat(m.getSavedGoals()).isEqualByComparingTo("1000000");
        assertThat(m.getLeftOver()).isEqualByComparingTo("-1400000");
        assertThat(m.getPayForOtherMonths()).isEqualByComparingTo("7170000");
        assertThat(m.getWalletChange()).isEqualByComparingTo("5770000");
        assertThat(m.getCount()).isEqualTo(2);
    }

    @Test
    void october_theExpectedMonthIsSeptemberWithEverydayScaledBy31Over30() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);
        Expected e = r.getExpected();

        Flow x = e.getFlow();
        assertThat(x.getEarned()).isEqualByComparingTo("41204000");
        assertThat(x.getEarnedPay()).isEqualByComparingTo("15059000");
        assertThat(x.getEarnedBonus()).isEqualByComparingTo("26045000");          // ≥ 25%: "(incl. 26 M bonus)"
        assertThat(x.getEarnedOther()).isEqualByComparingTo("100000");
        assertThat(x.getEveryday()).isEqualByComparingTo("15537200");
        assertThat(x.getEverydayUnitemised()).isEqualByComparingTo("4182933");
        assertThat(x.getBills()).isEqualByComparingTo("5300000");
        assertThat(x.getLoanPayments()).isEqualByComparingTo("1955000");
        assertThat(x.getOut()).isEqualByComparingTo("22792200");
        assertThat(x.getSaved()).isEqualByComparingTo("4520000");
        assertThat(x.getSavedDonation()).isEqualByComparingTo("800000");
        assertThat(x.getSavedEmergency()).isEqualByComparingTo("720000");
        assertThat(x.getSavedInvestments()).isEqualByComparingTo("3000000");
        assertThat(x.getSavedGoals()).isEqualByComparingTo("0");
        assertThat(x.getLeftOver()).isEqualByComparingTo("13891800");
        assertThat(x.getBorrowed()).isEqualByComparingTo("1955000");
        assertThat(x.getLent()).isEqualByComparingTo("1000000");
        assertThat(x.getReturned()).isEqualByComparingTo("700000");
        assertThat(x.getFromSavings()).isEqualByComparingTo("0");
        assertThat(x.getPayForOtherMonths()).isNull();
        assertThat(x.getWalletChange()).isNull();
        assertThat(x.getCount()).isZero();
        assertThat(e.getEverydayByToday()).isEqualByComparingTo("732533");      // 10,988,000 × 2 ÷ 30
        assertThat(e.getPaidOffLoans()).isEqualByComparingTo("1955000");
    }

    @Test
    void october_outIsSoFarPlusToComePlusThePaidOffLoans() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);
        MonthFlow m = r.getMonths().get(0);
        Expected e = r.getExpected();

        BigDecimal toCome = e.getFlow().getOut().subtract(m.getOut()).subtract(e.getPaidOffLoans());
        assertThat(toCome).isEqualByComparingTo("20437200");                     // "20,4 M to come"
        assertThat(m.getOut().add(toCome).add(e.getPaidOffLoans())).isEqualByComparingTo("22792200");
        // Set aside: 4,520,000 expected, 3,520,000 to come.
        assertThat(e.getFlow().getSaved().subtract(m.getSaved())).isEqualByComparingTo("3520000");
    }

    @Test
    void october_theSalaryReceivedOn2OctoberCountsInSeptember() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);

        assertThat(r.getReceivedForOtherMonths()).extracting("date", "countedIn", "amount", "categoryId", "name", "nameUz")
                .containsExactly(tuple(LocalDate.of(2026, 10, 2), "2026-09", n("7170000"), 24L, "Salary", "Maosh"));
        assertThat(r.getStableIncome()).isEqualByComparingTo("8000000");
    }

    @Test
    void october_everyOutLineHasItsExpected_inSeptembersOrder() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);

        assertThat(r.getOut()).extracting("key", "amount", "expected").containsExactly(
                tuple("cat:9", n("0"), n("7700933")),
                tuple("unitemised", n("0"), n("4182933")),
                tuple("cat:25", n("0"), n("4081667")),
                tuple("loans", n("400000"), n("1955000")),
                tuple("cat:7", n("0"), n("1717400")),
                tuple("cat:31", n("0"), n("1395000")),
                tuple("cat:13", n("0"), n("1100000")),
                tuple("cat:27", n("0"), n("411267")),
                tuple("cat:11", n("0"), n("206667")),
                tuple("cat:28", n("0"), n("41333")));
        assertThat(r.getOut().stream().map(Line::getExpected).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("22792200");
        assertThat(child(line(r.getOut(), "cat:9"), "bill:3").getExpected()).isEqualByComparingTo("4200000");
        assertThat(line(r.getOut(), "unitemised").getChildren()).isEmpty();    // September's check days are not carried
    }

    @Test
    void october_theLoansAlreadyPaidOffAreClosed_theBankLoanIsNew() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);

        Line loans = line(r.getOut(), "loans");
        // §4.2's order: a new loan, and one paid off before the month, rank by what they are so far.
        assertThat(loans.getChildren()).extracting("key", "amount", "expected", "isNew", "closed").containsExactly(
                tuple("loan:BANK:1", n("400000"), null, true, false),
                tuple("loan:LOAN:5", n("0"), n("1155000"), false, true),
                tuple("loan:LOAN:6", n("0"), n("800000"), false, true));
        assertThat(child(loans, "loan:BANK:1").getName()).isEqualTo("Xalq Banki · Talim kredit");
        assertThat(child(loans, "loan:BANK:1").getLoanKind()).isEqualTo("BANK");
    }

    @Test
    void october_everyIncomeSourceIsListedAtZeroWithItsExpected() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);

        assertThat(r.getIncome()).extracting("key", "amount", "expected").containsExactly(
                tuple("cat:1", n("0"), n("41104000")), tuple("cat:6", n("0"), n("100000")));
        assertThat(line(r.getIncome(), "cat:1").getChildren()).extracting("key", "amount", "expected", "isNew")
                .containsExactly(tuple("cat:23", n("0"), n("26045000"), false),
                        tuple("cat:24", n("0"), n("13059000"), false),
                        tuple("cat:26", n("0"), n("2000000"), false));
    }

    @Test
    void october_setAsideGroups_goalsIsNew() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);

        assertThat(r.getSetAside()).extracting("key", "amount", "expected", "isNew").containsExactly(
                tuple("group:INVESTMENTS", n("0"), n("3000000"), false),
                tuple("group:EMERGENCY", n("0"), n("720000"), false),
                tuple("group:GOALS", n("1000000"), null, true),
                tuple("group:DONATIONS", n("0"), n("800000"), false));
        assertThat(line(r.getSetAside(), "group:GOALS").getChildren()).extracting("key", "kind", "name", "amount", "isNew")
                .containsExactly(tuple("goal:11", "GOAL", "Ota-onam (parents)", n("1000000"), true));
        assertThat(child(line(r.getSetAside(), "group:GOALS"), "goal:11").getHistory().getInvestmentId()).isEqualTo(11L);
        assertThat(child(line(r.getSetAside(), "group:INVESTMENTS"), "holding:2").getExpected()).isEqualByComparingTo("3000000");
        assertThat(child(line(r.getSetAside(), "group:DONATIONS"), "donation:30").getExpected()).isEqualByComparingTo("800000");
        assertThat(r.getSinceStart().getSetAside()).isEqualByComparingTo("5520000");
        assertThat(r.getSetAside()).extracting("sinceStart")
                .containsExactly(n("3000000"), n("720000"), n("1000000"), n("800000"));
        assertThat(r.getSinceStart().getFromSavings()).isEqualByComparingTo("0");
    }

    @Test
    void october_worthNowIsEveryHoldingAtItsValueNow() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);

        assertThat(r.getHoldingsNow().stream().filter(h -> h.getValue().signum() != 0).toList())
                .extracting("refId", "name", "kind", "value", "putIn", "valueTracked").containsExactly(
                        tuple(2L, "IMAN", "INVESTMENT", n("17610147"), n("17300000"), true),
                        tuple(1L, "Asaxiy", "INVESTMENT", n("2005827"), n("2000000"), true),
                        tuple(3L, "IMAN (Emergency)", "EMERGENCY", n("1413254"), n("1400000"), true),
                        tuple(11L, "Ota-onam (parents)", "GOAL", n("1000000"), n("1000000"), false));
        assertThat(r.getHoldingsNow().stream().map(Holding::getValue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("22029228");
        assertThat(r.getHoldingsNow()).noneMatch(h -> h.getRefId() == null);   // no "Emergency fund (no account)"
    }

    @Test
    void october_nothingMovedSoTheFourMovedGroupsAreSentAtZero() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);

        assertThat(r.getMoved()).extracting("key", "amount", "expected", "children")
                .containsExactly(tuple("moved:BORROWED", n("0"), null, List.of()),
                        tuple("moved:LENT", n("0"), null, List.of()),
                        tuple("moved:RETURNED", n("0"), null, List.of()),
                        tuple("moved:FROM_SAVINGS", n("0"), null, List.of()));
    }

    @Test
    void october_theGoals_putInEqualsSetAsidesGoalsGroup_ofTheAsked() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);

        assertThat(r.getGoals()).extracting("refId", "name", "kind").containsExactly(
                tuple(9L, "Atam uchun Samsung S26 ultra", "PLAN"),
                tuple(7L, "Lobarxon uchun MacBook", "PLAN"),
                tuple(11L, "Ota-onam (parents)", "PLAN"),
                tuple(8L, "IPhone 19 / 18 pro max", "WISH"));
        BigDecimal putIn = r.getGoals().stream().map(g -> month(g, OCT).getPutIn()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal asked = r.getGoals().stream().map(g -> month(g, OCT).getAsked()).filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(putIn).isEqualByComparingTo("1000000").isEqualByComparingTo(line(r.getSetAside(), "group:GOALS").getAmount());
        assertThat(asked).isEqualByComparingTo("8500000");
    }

    @Test
    void october_eachGoalCard() {
        AnalyticsBreakdownResponse r = new OwnerLiveFixture().breakdown.breakdown(OCT, OCT, TODAY);

        Goal samsung = goal(r, 9);
        assertThat(samsung.getDeadline()).isEqualTo("2026-12");
        assertThat(samsung.getTarget()).isEqualByComparingTo("12000000");
        assertThat(samsung.getValueNow()).isEqualByComparingTo("0");
        assertThat(samsung.getMonths()).extracting("month", "putIn", "asked", "reachedEnd")
                .containsExactly(tuple("2026-10", n("0"), n("4000000"), n("0")));
        assertThat(samsung.getLastPutIn()).isNull();                             // "Nothing put in yet"

        Goal macbook = goal(r, 7);
        assertThat(macbook.getDeadline()).isEqualTo("2026-12");
        assertThat(macbook.getTarget()).isEqualByComparingTo("10000000");
        assertThat(month(macbook, OCT).getAsked()).isEqualByComparingTo("3500000");
        assertThat(macbook.getLastPutIn()).isNull();

        Goal parents = goal(r, 11);
        assertThat(parents.getDeadline()).isNull();
        assertThat(parents.getTarget()).isEqualByComparingTo("50000000");
        assertThat(parents.getMonthly()).isEqualByComparingTo("1000000");
        assertThat(parents.getStartMonth()).isEqualTo("2026-10");
        assertThat(parents.getCreatedMonth()).isEqualTo("2026-10");
        assertThat(parents.getValueNow()).isEqualByComparingTo("1000000");     // 1 M of 50 M = 2%
        assertThat(parents.isValueTracked()).isFalse();
        assertThat(parents.getPutInTotal()).isEqualByComparingTo("1000000");
        assertThat(parents.getTakenOutTotal()).isEqualByComparingTo("0");
        assertThat(parents.getLastPutIn()).isEqualTo("2026-10");                 // "+1 M put in"
        assertThat(parents.getMonths()).extracting("month", "putIn", "takenOut", "net", "asked", "reachedEnd",
                "approximate", "fromSnapshot").containsExactly(tuple("2026-10", n("1000000"), n("0"), n("1000000"),
                n("1000000"), n("1000000"), false, false));
        assertThat(month(parents, OCT).getHistory().getInvestmentId()).isEqualTo(11L);

        Goal iphone = goal(r, 8);
        assertThat(iphone.getTarget()).isEqualByComparingTo("40000000");
        assertThat(iphone.getMonthly()).isNull();                                // asks nothing
        assertThat(month(iphone, OCT).getAsked()).isNull();
        assertThat(iphone.getLastPutIn()).isNull();
    }

    @Test
    void october_bonusSwitchedOff_whatTheWebSubtracts() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);
        Expected e = r.getExpected();

        BigDecimal in = e.getFlow().getEarned().subtract(e.getFlow().getEarnedBonus());
        BigDecimal leftOver = e.getFlow().getLeftOver().subtract(e.getFlow().getEarnedBonus());
        assertThat(in).isEqualByComparingTo("15159000");                         // Q1: "Expected In 15,2 M"
        assertThat(leftOver).isEqualByComparingTo("-12153200");                  // Q1: "−12,2 M"
    }

    @Test
    void october_everydayDailyRunsToTheOwnersDay() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);

        assertThat(r.getEverydayDaily()).extracting("day", "amount", "cumulative")
                .containsExactly(tuple(1, n("0"), n("0")), tuple(2, n("0"), n("0")));
    }

    // ── §6.1 12 months ──────────────────────────────────────────────────────

    @Test
    void twelveMonths_theRangeStartsAtTheHistoryStart() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(YearMonth.of(2025, 11), OCT, TODAY);

        assertThat(r.getFrom()).isEqualTo("2025-11");
        assertThat(r.getTo()).isEqualTo("2026-10");
        assertThat(r.getMonths()).extracting("month").containsExactly("2026-09", "2026-10");
        assertThat(r.getHistory().getTracked()).hasSize(2);                      // the 12 months tab is open
        assertThat(r.getExpected()).isNull();
        assertThat(r.getEverydayDaily()).isEmpty();
        assertThat(r.getReceivedForOtherMonths()).isEmpty();
    }

    @Test
    void twelveMonths_totalsAndAMonthOnAverage() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(YearMonth.of(2025, 11), OCT, TODAY);

        assertThat(r.getTotal().getEarned()).isEqualByComparingTo("41204000");
        assertThat(r.getTotal().getOut()).isEqualByComparingTo("22691000");
        assertThat(r.getTotal().getSaved()).isEqualByComparingTo("5520000");
        assertThat(r.getTotal().getLeftOver()).isEqualByComparingTo("12993000");
        assertThat(r.getAverage().getBasedOn()).containsExactly("2026-09");
        Flow a = r.getAverage().getFlow();
        assertThat(a.getEarned()).isEqualByComparingTo("41204000");
        assertThat(a.getOut()).isEqualByComparingTo("22291000");
        assertThat(a.getSaved()).isEqualByComparingTo("4520000");
        assertThat(a.getLeftOver()).isEqualByComparingTo("14393000");
        assertThat(a.getWalletChange()).isNull();
        assertThat(a.getPayForOtherMonths()).isNull();
    }

    @Test
    void twelveMonths_theMinis() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(YearMonth.of(2025, 11), OCT, TODAY);

        // In: the income leaves, one slot per months[] entry.
        Line salary = line(r.getIncome(), "cat:1");
        assertThat(salary.getChildren()).extracting("key", "amount", "byMonth").containsExactly(
                tuple("cat:23", n("26045000"), List.of(n("26045000"), n("0"))),
                tuple("cat:24", n("13059000"), List.of(n("13059000"), n("0"))),
                tuple("cat:26", n("2000000"), List.of(n("2000000"), n("0"))));
        assertThat(line(r.getIncome(), "cat:6").getByMonth()).containsExactly(n("100000"), n("0"));
        assertThat(line(r.getIncome(), "cat:6").getChildren()).isEmpty();        // a root with no children is its own leaf

        // Out: the top 4 lines, the rest summed by the web as "Other".
        List<Line> top4 = r.getOut().subList(0, 4);
        assertThat(top4).extracting("key", "amount").containsExactly(
                tuple("cat:9", n("7588000")), tuple("unitemised", n("4048000")),
                tuple("cat:25", n("3950000")), tuple("loans", n("2355000")));
        assertThat(r.getTotal().getOut().subtract(sumAmounts(top4))).isEqualByComparingTo("4750000");

        assertThat(r.getSetAside()).extracting("amount")
                .containsExactly(n("3000000"), n("720000"), n("1000000"), n("800000"));
        assertThat(goal(r, 11).getPutInTotal()).isEqualByComparingTo("1000000");
        assertThat(r.getGoals()).filteredOn(g -> g.getRefId() != 11L).allSatisfy(g ->
                assertThat(g.getValueNow()).isEqualByComparingTo("0"));
    }

    @Test
    void twelveMonths_aRangeHasAveragesAndNoExpectedOrHistoryLinks() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(YearMonth.of(2025, 11), OCT, TODAY);

        Line housing = line(r.getOut(), "cat:9");
        assertThat(housing.getAverage()).isEqualByComparingTo("7588000");
        assertThat(housing.getExpected()).isNull();
        assertThat(housing.getHistory()).isNull();
        assertThat(line(r.getMoved(), "moved:BORROWED").getAverage()).isNull();
    }

    @Test
    void twelveMonths_getAnalyticsCountsTheRangeTheSameWay() {
        OwnerLiveFixture f = new OwnerLiveFixture();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(YearMonth.of(2025, 11), OCT, TODAY);

        AnalyticsResponse old = f.analytics.analytics(YearMonth.of(2025, 11), OCT, TODAY);
        assertThat(old.getTotals().getEarned()).isEqualByComparingTo(r.getTotal().getEarned());
        assertThat(old.getTotals().getOut()).isEqualByComparingTo(r.getTotal().getOut());
        assertThat(old.getTotals().getSaved()).isEqualByComparingTo(r.getTotal().getSaved());
        assertThat(old.getTotals().getWalletChange()).isEqualByComparingTo(r.getTotal().getWalletChange());
    }

    // ── §6.1 After Q2 = August (the 7 Sep salary marked 2026-08) ─────────────

    @Test
    void afterQ2_historyStillStartsInSeptember() {
        OwnerLiveFixture f = afterQ2();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);

        assertThat(r.getHistory().getStart()).isEqualTo("2026-09");
        assertThat(r.getHistory().getFirstEarned()).isEqualTo("2026-08");
        assertThat(r.getHistory().getTracked()).containsExactly("2026-09", "2026-10");
        assertThat(f.breakdown.breakdown(YearMonth.of(2025, 11), OCT, TODAY).getMonths())
                .extracting("month").containsExactly("2026-09", "2026-10");
    }

    @Test
    void afterQ2_septemberHoldsOneSalary() {
        OwnerLiveFixture f = afterQ2();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, TODAY);
        MonthFlow m = r.getMonths().get(0);

        assertThat(m.getEarned()).isEqualByComparingTo("35315000");
        assertThat(m.getEarnedPay()).isEqualByComparingTo("9170000");
        assertThat(line(r.getIncome(), "cat:1").getAmount()).isEqualByComparingTo("35215000");
        assertThat(line(r.getIncome(), "cat:1").getChildren()).extracting("key", "amount").containsExactly(
                tuple("cat:23", n("26045000")), tuple("cat:24", n("7170000")), tuple("cat:26", n("2000000")));
        assertThat(m.getLeftOver()).isEqualByComparingTo("8504000");
        assertThat(m.getWalletChange()).isEqualByComparingTo("8878000");
        // "7,2 M of it received 2 Oct" · "5,9 M received 7 Sep counts in August"
        assertThat(child(line(r.getIncome(), "cat:1"), "cat:24").getOtherMonth())
                .extracting("date", "amount").containsExactly(tuple(LocalDate.of(2026, 10, 2), n("7170000")));
        assertThat(r.getReceivedForOtherMonths()).extracting("date", "countedIn", "amount")
                .containsExactly(tuple(sep(7), "2026-08", n("5889000")));
    }

    @Test
    void afterQ2_octobersExpected() {
        OwnerLiveFixture f = afterQ2();

        Flow x = f.breakdown.breakdown(OCT, OCT, TODAY).getExpected().getFlow();
        assertThat(x.getEarned()).isEqualByComparingTo("35315000");
        assertThat(x.getEarnedPay()).isEqualByComparingTo("9170000");
        assertThat(x.getLeftOver()).isEqualByComparingTo("8002800");
        // With the bonus switched off (Q1): In 9,270,000 · Left over −18,042,200.
        assertThat(x.getEarned().subtract(x.getEarnedBonus())).isEqualByComparingTo("9270000");
        assertThat(x.getLeftOver().subtract(x.getEarnedBonus())).isEqualByComparingTo("-18042200");
    }

    @Test
    void afterQ2_twelveMonths() {
        OwnerLiveFixture f = afterQ2();
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(YearMonth.of(2025, 11), OCT, TODAY);

        assertThat(r.getTotal().getEarned()).isEqualByComparingTo("35315000");
        assertThat(r.getTotal().getLeftOver()).isEqualByComparingTo("7104000");
        assertThat(r.getAverage().getFlow().getEarned()).isEqualByComparingTo("35315000");
        assertThat(r.getAverage().getFlow().getLeftOver()).isEqualByComparingTo("8504000");
        assertThat(child(line(r.getIncome(), "cat:1"), "cat:24").getAmount()).isEqualByComparingTo("7170000");
    }

    // ── The rules, one by one (§4.7) ────────────────────────────────────────

    @Test
    void groupKeysAreAlwaysSent_evenWithNothingRecorded() {
        OwnerLiveFixture f = new OwnerLiveFixture(false);
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(null, null, TODAY);

        assertThat(r.getHistory().getStart()).isNull();
        assertThat(r.getHistory().getTracked()).isEmpty();
        assertThat(r.getMonths()).isEmpty();
        assertThat(r.getSinceStart()).isNull();
        assertThat(r.getOut()).extracting("key").containsExactlyInAnyOrder("unitemised", "loans");
        assertThat(r.getSetAside()).extracting("key")
                .containsExactly("group:INVESTMENTS", "group:EMERGENCY", "group:GOALS", "group:DONATIONS");
        assertThat(r.getSetAside()).allSatisfy(l -> assertThat(l.getSinceStart()).isEqualByComparingTo("0"));
        assertThat(r.getMoved()).extracting("key")
                .containsExactly("moved:BORROWED", "moved:LENT", "moved:RETURNED", "moved:FROM_SAVINGS");
        assertThat(r.getIncome()).isEmpty();
        assertThat(r.getEverydayDaily()).isEmpty();
    }

    @Test
    void aBackDatedJuneRowCreatesNoMonth_evenWithoutATrackingStart() {
        for (boolean trackingStart : new boolean[]{true, false}) {
            OwnerLiveFixture f = new OwnerLiveFixture();

            if (!trackingStart) f.settings.setAllocationTrackingStartMonth(null);
            f.row(LocalDate.of(2026, 6, 10), TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "3000000",
                    f.loanReceived, "Old loan");

            AnalyticsBreakdownResponse r = f.breakdown.breakdown(YearMonth.of(2025, 11), OCT, TODAY);

            assertThat(r.getHistory().getStart()).isEqualTo("2026-09");
            assertThat(r.getMonths()).extracting("month").containsExactly("2026-09", "2026-10");
            assertThat(r.getTotal().getBorrowed()).isEqualByComparingTo("1955000");
            assertThat(line(r.getMoved(), "moved:BORROWED").getAmount()).isEqualByComparingTo("1955000");
        }
    }

    @Test
    void anUntrackedMonthIsSkippedAndNamed_everydayScaledOverTheBaseMonthsDays() {
        OwnerLiveFixture f = new OwnerLiveFixture(false);

        f.income(LocalDate.of(2026, 6, 5), "8000000", f.salary, "Salary", null);
        f.spend(LocalDate.of(2026, 6, 10), "300000", f.food, "Bozor");
        // July: nothing at all.
        f.income(LocalDate.of(2026, 8, 5), "8000000", f.salary, "Salary", null);
        f.spend(LocalDate.of(2026, 8, 10), "600000", f.food, "Bozor");

        AnalyticsBreakdownResponse r = f.breakdown.breakdown(SEP, SEP, LocalDate.of(2026, 9, 10));

        assertThat(r.getHistory().getTracked()).containsExactly("2026-06", "2026-08");
        assertThat(r.getExpected().getBasedOn()).containsExactly("2026-06", "2026-08");
        assertThat(r.getExpected().getSkipped()).containsExactly("2026-07");
        // 900,000 × 30 ÷ (30 + 31) = 442,622.95…, rounded once.
        assertThat(r.getExpected().getFlow().getEveryday()).isEqualByComparingTo("442623");
        assertThat(line(r.getOut(), "cat:7").getExpected()).isEqualByComparingTo("442623");
        assertThat(r.getExpected().getFlow().getEarned()).isEqualByComparingTo("8000000");
        assertThat(r.getExpected().getDayFactor()).isEqualByComparingTo("0.9836");     // 30 ÷ 30.5
        // September itself, still at no rows, is in the months but not tracked.
        assertThat(r.getMonths()).extracting("month", "tracked").containsExactly(tuple("2026-09", false));
    }

    @Test
    void februarysEverydayIsScaledExactly_andRoundedOnce() {
        OwnerLiveFixture f = new OwnerLiveFixture(false);

        f.income(LocalDate.of(2027, 1, 5), "8000000", f.salary, "Salary", null);
        f.spend(LocalDate.of(2027, 1, 10), "900000", f.food, "Bozor");
        f.check(LocalDate.of(2027, 1, 20), "100000");

        AnalyticsBreakdownResponse r = f.breakdown.breakdown(null, null, LocalDate.of(2027, 2, 10));
        Expected e = r.getExpected();

        // 1,000,000 × 28 ÷ 31 = 903,225.8 → 903,226 (a day's 32,258 × 28 would give 903,224).
        assertThat(e.getFlow().getEveryday()).isEqualByComparingTo("903226");
        assertThat(e.getFlow().getEverydayUnitemised()).isEqualByComparingTo("90323");   // 90,322.58
        assertThat(line(r.getOut(), "cat:7").getExpected()).isEqualByComparingTo("812903"); // 812,903.2
        assertThat(line(r.getOut(), "unitemised").getExpected()).isEqualByComparingTo("90323");
        assertThat(e.getEverydayByToday()).isEqualByComparingTo("290323");              // 900,000 × 10 ÷ 31
        assertThat(e.getDayFactor()).isEqualByComparingTo("0.9032");
        assertThat(e.getFlow().getOut()).isEqualByComparingTo(e.getFlow().getEveryday());
    }

    @Test
    void aNewIncomeRootIsListedAndMarkedNew() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        Category family = f.category(40, "Family", "Oiladan", CategoryType.INCOME, null);
        f.income(OCT.atDay(1), "500000", family, "Dadamdan", null);

        Line line = line(f.breakdown.breakdown(OCT, OCT, TODAY).getIncome(), "cat:40");

        assertThat(line.getAmount()).isEqualByComparingTo("500000");
        assertThat(line.getIsNew()).isTrue();
        assertThat(line.getExpected()).isNull();
        assertThat(line.getIncomeKind()).isEqualTo("OTHER");
        assertThat(line.getNameUz()).isEqualTo("Oiladan");
    }

    @Test
    void aRootWithRowsOfItsOwnAndChildrenGetsASelfLine_soTheChildrenAddUp() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        f.income(sep(25), "1000000", f.salaryRoot, "Booked on the root", null);

        Line sepRoot = line(f.breakdown.breakdown(SEP, SEP, TODAY).getIncome(), "cat:1");
        assertThat(sepRoot.getAmount()).isEqualByComparingTo("42104000");
        assertThat(sumAmounts(sepRoot.getChildren())).isEqualByComparingTo("42104000");
        Line self = child(sepRoot, "cat:1:self");
        assertThat(self.getName()).isEqualTo("Salary");
        assertThat(self.getNameUz()).isEqualTo("Oylik maosh");
        assertThat(self.getAmount()).isEqualByComparingTo("1000000");
        assertThat(self.getHistory()).isNull();                                       // categoryId=1 also lists its children

        // A lasting key: carried into October at 0 with its expected.
        Line octSelf = child(line(f.breakdown.breakdown(OCT, OCT, TODAY).getIncome(), "cat:1"), "cat:1:self");
        assertThat(octSelf.getAmount()).isEqualByComparingTo("0");
        assertThat(octSelf.getExpected()).isEqualByComparingTo("1000000");
    }

    @Test
    void dayEntryAndUnknownKeysAreNeverCarriedAndNeverGetAnExpected() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        f.row(sep(20), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "50000", f.loanRepayment, "Kimdir");
        f.save(sep(20), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "70000", 99L, f.investment, "Gone");
        f.check(OCT.atDay(1), "30000");

        AnalyticsBreakdownResponse sep = f.breakdown.breakdown(SEP, SEP, TODAY);
        assertThat(line(sep.getOut(), "loans").getChildren()).extracting("key").contains("loan:?:Kimdir");
        assertThat(line(sep.getSetAside(), "group:INVESTMENTS").getChildren()).extracting("key").contains("holding:?:Gone");
        assertThat(line(sep.getMoved(), "moved:LENT").getChildren()).extracting("key").contains("desc:Aziz");

        AnalyticsBreakdownResponse oct = f.breakdown.breakdown(OCT, OCT, TODAY);
        assertThat(line(oct.getOut(), "loans").getChildren()).extracting("key")
                .containsExactly("loan:BANK:1", "loan:LOAN:5", "loan:LOAN:6");
        assertThat(line(oct.getSetAside(), "group:INVESTMENTS").getChildren()).extracting("key").containsExactly("holding:2");
        assertThat(line(oct.getMoved(), "moved:LENT").getChildren()).isEmpty();
        assertThat(line(oct.getMoved(), "moved:BORROWED").getChildren()).isEmpty();
        // October's own check day is listed, with no expected and not "new".
        assertThat(line(oct.getOut(), "unitemised").getChildren()).extracting("key", "amount", "expected", "isNew")
                .containsExactly(tuple("check:2026-10-01", n("30000"), null, false));
    }

    @Test
    void aLoanPaidOffBeforeTheMonthIsClosed_andCountedInPaidOffLoans() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        // In September — the month of its last repayment — it is not closed.
        assertThat(line(f.breakdown.breakdown(SEP, SEP, TODAY).getOut(), "loans").getChildren())
                .noneMatch(Line::isClosed);
        AnalyticsBreakdownResponse oct = f.breakdown.breakdown(OCT, OCT, TODAY);
        assertThat(line(oct.getOut(), "loans").getChildren()).filteredOn(Line::isClosed)
                .extracting("key").containsExactly("loan:LOAN:5", "loan:LOAN:6");
        assertThat(oct.getExpected().getPaidOffLoans()).isEqualByComparingTo("1955000");

        // Not paid off now: not closed, whatever its repayments.
        f.loansTaken.get(0).setStatus(uz.tracker.trackerproject.enums.RecordStatus.PARTIALLY_PAID);
        AnalyticsBreakdownResponse open = f.breakdown.breakdown(OCT, OCT, TODAY);
        assertThat(child(line(open.getOut(), "loans"), "loan:LOAN:5").isClosed()).isFalse();
        assertThat(open.getExpected().getPaidOffLoans()).isEqualByComparingTo("800000");
    }

    @Test
    void noEarnedRowYet_historyStartsAtTheFirstOutMonth_orAtTheFirstRowOfAnyKind() {
        OwnerLiveFixture f = new OwnerLiveFixture(false);

        f.save(LocalDate.of(2026, 8, 10), TransactionSubType.DONATION, AllocationBucket.DONATION, "100000", null, f.mosque, "Masjid");
        assertThat(f.breakdown.breakdown(null, null, TODAY).getHistory().getStart()).isEqualTo("2026-08");

        f.spend(sep(3), "50000", f.food, "Non");
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(null, null, TODAY);

        assertThat(r.getHistory().getFirstEarned()).isNull();
        assertThat(r.getHistory().getFirstOut()).isEqualTo("2026-09");
        assertThat(r.getHistory().getStart()).isEqualTo("2026-09");
        assertThat(r.getHistory().getTracked()).containsExactly("2026-09");
    }

    @Test
    void aHoldingsCreatingRowIsCounted_andItsLineHasNoHistoryLink() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        Transaction created = f.save(sep(5), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "2000000",
                null, f.investment, "Gold");
        Investment gold = f.holding(60, "Gold", InvestmentType.GOLD, "2500000", null, sep(5), false);
        gold.setOriginatingTransactionId(created.getId());
        f.save(sep(20), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "500000", 60L, f.investment, "Gold");
        f.save(OCT.atDay(1), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "300000", 60L, f.investment, "Gold");

        Line sepGold = child(line(f.breakdown.breakdown(SEP, SEP, TODAY).getSetAside(), "group:INVESTMENTS"), "holding:60");
        assertThat(sepGold.getAmount()).isEqualByComparingTo("2500000");
        assertThat(sepGold.getCount()).isEqualTo(2);
        assertThat(sepGold.getHistory()).isNull();

        Line octGold = child(line(f.breakdown.breakdown(OCT, OCT, TODAY).getSetAside(), "group:INVESTMENTS"), "holding:60");
        assertThat(octGold.getHistory().getInvestmentId()).isEqualTo(60L);
    }

    /** Σ goals' put-in in month {@code m} = the Goals group on Set aside (§3.5). */
    private static void assertGoalsPutInIsTheGoalsGroup(AnalyticsBreakdownResponse r, YearMonth m) {
        BigDecimal putIn = r.getGoals().stream().flatMap(g -> g.getMonths().stream())
                .filter(x -> x.getMonth().equals(m.toString())).map(GoalMonth::getPutIn)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(putIn).isEqualByComparingTo(line(r.getSetAside(), "group:GOALS").getAmount());
    }

    @Test
    void turningAHoldingIntoAGoalLaterLeavesGoalsPutInEqualToTheGoalsGroup() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        Investment car = f.holding(50, "Car", InvestmentType.OTHER, "2500000", null, sep(1), false);
        f.save(sep(10), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "2000000", 50L, f.investment, "Car");
        // Made a goal afterwards: the September row keeps the bucket it was saved with.
        car.setSavingsGoal(true);
        car.setTargetAmount(n("30000000"));
        car.setMonthlyContribution(n("500000"));
        f.save(OCT.atDay(1), TransactionSubType.INVESTMENT, AllocationBucket.SAVINGS, "500000", 50L, f.investment, "Car");

        for (YearMonth m : List.of(SEP, OCT)) assertGoalsPutInIsTheGoalsGroup(f.breakdown.breakdown(m, m, TODAY), m);
        AnalyticsBreakdownResponse year = f.breakdown.breakdown(YearMonth.of(2025, 11), OCT, TODAY);
        assertThat(year.getGoals().stream().map(Goal::getPutInTotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(line(year.getSetAside(), "group:GOALS").getAmount())
                .isEqualByComparingTo("1500000");
        assertThat(goal(year, 50).getPutInTotal()).isEqualByComparingTo("500000");
    }

    @Test
    void turningAGoalBackIntoAPlainHoldingLeavesItsGoalMoneyOnBothPages_asAWishThatAsksNothing() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        // Ota-onam is no goal now: its 1 October row keeps the SAVINGS bucket it was saved with.
        f.holding(11).setSavingsGoal(false);

        for (YearMonth m : List.of(SEP, OCT)) assertGoalsPutInIsTheGoalsGroup(f.breakdown.breakdown(m, m, TODAY), m);
        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);
        assertThat(line(r.getSetAside(), "group:GOALS").getAmount()).isEqualByComparingTo("1000000");
        Goal parents = goal(r, 11);
        assertThat(parents.getKind()).isEqualTo("WISH");
        assertThat(parents.getMonthly()).isNull();
        assertThat(parents.getPutInTotal()).isEqualByComparingTo("1000000");
        assertThat(month(parents, OCT).getPutIn()).isEqualByComparingTo("1000000");
        assertThat(month(parents, OCT).getAsked()).isNull();
        assertThat(r.getGoals().stream().map(g -> month(g, OCT).getAsked()).filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("7500000");
        assertThat(r.getGoals()).extracting("refId").containsExactly(9L, 7L, 8L, 11L);  // the wishes last, by name
        assertThat(r.getGoals()).noneMatch(g -> g.getRefId() == 2L);                 // a holding that took no goal money

        AnalyticsBreakdownResponse year = f.breakdown.breakdown(YearMonth.of(2025, 11), OCT, TODAY);
        assertThat(year.getGoals().stream().map(Goal::getPutInTotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(line(year.getSetAside(), "group:GOALS").getAmount());
    }

    // ── Goals: reached at a month's end, exact or from a snapshot (§4.3, §4.6) ──

    /** A goal made in September: 1,000,000 put in on 10 Sep, 300,000 on 5 Oct... plus what a test adds. */
    private static Investment septemberGoal(OwnerLiveFixture f, String invested) {
        Investment g = f.goal(70, "House", invested, "100000000", "2000000", null, false);
        g.setPurchaseDate(sep(1));
        f.save(sep(10), TransactionSubType.INVESTMENT, AllocationBucket.SAVINGS, "1000000", 70L, f.investment, "House");
        f.save(OCT.atDay(1), TransactionSubType.INVESTMENT, AllocationBucket.SAVINGS, "300000", 70L, f.investment, "House");
        return g;
    }

    @Test
    void aGoalWhoseEverySomHasARowIsRebuiltExactly() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        septemberGoal(f, "1300000");

        Goal g = goal(f.breakdown.breakdown(OCT, OCT, TODAY), 70);

        assertThat(g.getMonths()).extracting("month", "putIn", "reachedEnd", "approximate", "asked").containsExactly(
                tuple("2026-09", n("1000000"), n("1000000"), false, n("2000000")),
                tuple("2026-10", n("300000"), n("1300000"), false, n("2000000")));
        assertThat(g.getLastPutIn()).isEqualTo("2026-10");
    }

    @Test
    void aRecordOnlyContributionMakesPastMonthsApproximate() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        septemberGoal(f, "1800000");                                                  // 500,000 added "not through a wallet"

        Goal g = goal(f.breakdown.breakdown(OCT, OCT, TODAY), 70);

        assertThat(month(g, SEP).isApproximate()).isTrue();
        assertThat(month(g, SEP).getReachedEnd()).isEqualByComparingTo("1500000");  // value now − October's rows
        assertThat(month(g, SEP).isFromSnapshot()).isFalse();
        assertThat(month(g, OCT).isApproximate()).isFalse();                         // the month in progress is now
        assertThat(month(g, OCT).getReachedEnd()).isEqualByComparingTo("1800000");
    }

    @Test
    void aDailyOrRebuiltSnapshotIsUsedButKeepsTheMonthApproximate_aClosingOneMakesItExact() {
        for (String source : List.of(HoldingMonthSnapshot.DAILY, HoldingMonthSnapshot.REBUILT, HoldingMonthSnapshot.CLOSING)) {
            OwnerLiveFixture f = new OwnerLiveFixture();

            septemberGoal(f, "1800000");
            f.snapshot(70, SEP, source, "1600000", "1000000", "0");

            GoalMonth sep = month(goal(f.breakdown.breakdown(OCT, OCT, TODAY), 70), SEP);

            assertThat(sep.getReachedEnd()).isEqualByComparingTo("1600000");
            assertThat(sep.isFromSnapshot()).isTrue();
            assertThat(sep.isApproximate()).isEqualTo(!HoldingMonthSnapshot.CLOSING.equals(source));
        }
    }

    @Test
    void aRowBackDatedIntoASnapshottedMonthMovesThatMonthsReached_notTheNext() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        Investment g = septemberGoal(f, "1800000");
        f.snapshot(70, SEP, HoldingMonthSnapshot.CLOSING, "1600000", "1000000", "0");
        // Recorded later, dated 28 September.
        f.save(sep(28), TransactionSubType.INVESTMENT, AllocationBucket.SAVINGS, "200000", 70L, f.investment, "House");
        g.setInvestedAmount(n("2000000"));

        Goal goal = goal(f.breakdown.breakdown(OCT, OCT, TODAY), 70);

        assertThat(month(goal, SEP).getReachedEnd()).isEqualByComparingTo("1800000");
        assertThat(month(goal, SEP).getPutIn()).isEqualByComparingTo("1200000");
        assertThat(month(goal, OCT).getReachedEnd()).isEqualByComparingTo("2000000");
    }

    @Test
    void aRowOfAnotherBucketAlreadyInTheSnapshotDoesNotMoveTheCorrectedValue() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        septemberGoal(f, "2300000");
        // An INVESTMENTS-bucket row into the goal: not its Goals put-in, but it is in the linked net.
        f.save(sep(15), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "500000", 70L, f.investment, "House");
        f.snapshot(70, SEP, HoldingMonthSnapshot.DAILY, "1900000", "1500000", "0");

        GoalMonth sep = month(goal(f.breakdown.breakdown(OCT, OCT, TODAY), 70), SEP);

        assertThat(sep.getReachedEnd()).isEqualByComparingTo("1900000");
        assertThat(sep.getPutIn()).isEqualByComparingTo("1000000");                  // the GOAL rule
    }

    @Test
    void aPastMonthAsksByThePlanItsSnapshotNoted() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        Investment g = septemberGoal(f, "1300000");
        HoldingMonthSnapshot s = f.snapshot(70, SEP, HoldingMonthSnapshot.DAILY, "1000000", "1000000", "0");
        s.setMonthly(n("1500000"));
        s.setTarget(n("100000000"));
        g.setMonthlyContribution(n("2500000"));                                      // the plan was raised in October

        Goal goal = goal(f.breakdown.breakdown(OCT, OCT, TODAY), 70);

        assertThat(month(goal, SEP).getAsked()).isEqualByComparingTo("1500000");
        assertThat(month(goal, OCT).getAsked()).isEqualByComparingTo("2500000");
    }

    // ── The range (the same rules and words as GET /analytics) ──

    @Test
    void aRangeOutsideTheRulesIsRefused() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        assertThatThrownBy(() -> f.breakdown.breakdown(null, YearMonth.of(2026, 11), TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("to can't be after the month of date (2026-10), got: 2026-11");
        assertThatThrownBy(() -> f.breakdown.breakdown(OCT, SEP, TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("from can't be after to, got: 2026-10 and 2026-09");
        assertThatThrownBy(() -> f.breakdown.breakdown(YearMonth.of(2024, 10), OCT, TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The range can't be longer than 24 months, got: 25");
        assertThat(f.breakdown.breakdown(YearMonth.of(2024, 11), OCT, TODAY).getMonths()).hasSize(2);
    }

    @Test
    void aSalaryRecordedAheadForItsDayIsNotCountedYet() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        f.income(OCT.atDay(15), "2000000", f.avans, "Avans", null);

        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);

        assertThat(r.getNotYetCount()).isEqualTo(1);
        assertThat(r.getMonths().get(0).getEarned()).isEqualByComparingTo("0");
        // History's month view lists the row already, so no filter lists exactly the lines' (none).
        Line salary = line(r.getIncome(), "cat:1");
        assertThat(child(salary, "cat:26").getAmount()).isEqualByComparingTo("0");
        assertThat(child(salary, "cat:26").getHistory()).isNull();
        assertThat(salary.getHistory()).isNull();
        assertThat(child(salary, "cat:24").getHistory().getCategoryId()).isEqualTo(24L);
    }

    @Test
    void aBillPaidAheadForItsDayGetsNoLinkThatWouldListIt() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        // On 2 October, October's rent is recorded for the 5th.
        f.ledger.billPaid(OCT.atDay(5), "4200000", 3).setCategory(f.housing);

        AnalyticsBreakdownResponse r = f.breakdown.breakdown(OCT, OCT, TODAY);

        Line housing = line(r.getOut(), "cat:9");
        assertThat(child(housing, "bill:3").getAmount()).isEqualByComparingTo("0");
        assertThat(child(housing, "bill:3").getHistory()).isNull();                 // flow=BILL&categoryId=9 would list it
        assertThat(housing.getHistory()).extracting("categoryId", "flow").containsExactly(9L, "EVERYDAY");
        assertThat(r.getNotYetCount()).isEqualTo(1);
    }

    @Test
    void aGoalCardsLinkListsExactlyItsRowsOfTheMonth_orIsNotSent() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        septemberGoal(f, "1300000");
        // An INVESTMENTS-bucket row into the goal: History by the goal's id (or and SAVED) would list it,
        // the card does not count it.
        f.save(sep(15), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "500000", 70L, f.investment, "House");
        // Taken back out on 2 October: the card's "from savings", listed by the goal's id.
        f.row(TODAY, TransactionType.INCOME, TransactionSubType.INVESTMENT_WITHDRAWAL, "100000", null, "House")
                .setInvestmentId(70L);

        Goal g = goal(f.breakdown.breakdown(OCT, OCT, TODAY), 70);
        assertThat(month(g, SEP).getPutIn()).isEqualByComparingTo("1000000");
        assertThat(month(g, SEP).getHistory()).isNull();
        assertThat(month(g, OCT).getTakenOut()).isEqualByComparingTo("100000");
        assertThat(month(g, OCT).getHistory()).extracting("investmentId", "flow").containsExactly(70L, null);

        // A put-in recorded ahead for 9 October: History's October already lists it, the card does not yet.
        f.save(OCT.atDay(9), TransactionSubType.INVESTMENT, AllocationBucket.SAVINGS, "200000", 70L, f.investment, "House");
        assertThat(month(goal(f.breakdown.breakdown(OCT, OCT, TODAY), 70), OCT).getHistory()).isNull();
    }

    @Test
    void readingWritesNothing() {
        OwnerLiveFixture f = new OwnerLiveFixture();

        f.breakdown.breakdown(OCT, OCT, TODAY);
        org.mockito.Mockito.verify(f.snapshotRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }
}
