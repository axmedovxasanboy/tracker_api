package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AdvisorResponse;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Goal;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Means;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Owe;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.SavingsRow;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Suggestion;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The advisor's three new blocks — {@code means} (does a normal month have room for what is asked
 * of it?), {@code goals} (plans and wishes, with an honest status) and {@code owe} (the Loans
 * header) — and the rule that a wish asks for nothing, anywhere. Everything real runs: the Plan,
 * the daily walk and the advisor, over one ledger of transactions.
 */
class AdvisorMeansGoalsOweTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);
    private static final LocalDate MAY = LocalDate.of(2026, 5, 1);

    private AnalyticsFixture f;

    @BeforeEach
    void setUp() {
        f = new AnalyticsFixture();
        f.wallet("CARD", Currency.UZS, "9000000");
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private Investment goal(long id, String name, String target, String monthly) {
        Investment g = f.holding(id, name, "0", null, MAY);
        g.setSavingsGoal(true);
        g.setTargetAmount(target == null ? null : n(target));
        g.setMonthlyContribution(monthly == null ? null : n(monthly));
        return g;
    }

    /** Everyday spending so far this month: tracking began on 1 September, so the pace is this ÷ 20. */
    private void spent(String total) {
        f.spend(SEP.atDay(10), total, null, "Bozor");
    }

    private AdvisorResponse advise() {
        return f.advisor.advise(TODAY);
    }

    // ── A wish asks for nothing, anywhere ─────────────────────────────────────

    /**
     * The same month three ways: with a wish, without that goal at all, and with it made a plan
     * again. The first two must be indistinguishable wherever a goal asks for money.
     */
    @Test
    void aWishAsksForNothing_andIsAPlanAgainWithItsMonthlyPaymentIntact() {
        f.stableIncome("7000000", SEP.atDay(1));
        spent("1000000");
        goal(40, "Mashina", "20000000", "1000000");
        Investment macbook = goal(41, "MacBook", "10000000", "2000000");
        macbook.setWish(true);

        AdvisorResponse withWish = advise();

        // No GOAL row, nothing reserved, not in means — only the plan is.
        assertThat(withWish.getSavingsThisMonth()).filteredOn(r -> "GOAL".equals(r.getBucket()))
                .extracting(SavingsRow::getRefId).containsExactly(40L);
        assertThat(withWish.getMeans().getGoals()).isEqualByComparingTo("1000000");
        assertThat(withWish.getSuggestions()).extracting(Suggestion::getRefId).doesNotContain(41L);
        // It is still on the list, with its monthly payment kept.
        assertThat(withWish.getGoals()).extracting("id", "kind", "monthly", "status").containsExactly(
                tuple(40L, "PLAN", n("1000000"), "ON_TRACK"),
                tuple(41L, "WISH", n("2000000"), null));

        // Exactly the month it would be if the wish were not there.
        f.holdings.remove(macbook);
        AdvisorResponse without = advise();
        assertThat(withWish.getDaily().getSafePerDay()).isEqualByComparingTo(without.getDaily().getSafePerDay());
        assertThat(withWish.getDaily().getBreakdown().getGoals()).isEqualByComparingTo(without.getDaily().getBreakdown().getGoals());
        assertThat(withWish.getDaily().getBreakdown().getSavings()).isEqualByComparingTo(without.getDaily().getBreakdown().getSavings());
        assertThat(withWish.getDaily().getSafePerDayNoGoals()).isEqualByComparingTo(without.getDaily().getSafePerDayNoGoals());
        assertThat(withWish.getSetAsideLeft()).isEqualByComparingTo(without.getSetAsideLeft());
        assertThat(withWish.getFree()).isEqualByComparingTo(without.getFree());
        assertThat(withWish.getMeans().getLeftToLive()).isEqualByComparingTo(without.getMeans().getLeftToLive());
        assertThat(withWish.getSuggestions()).extracting(Suggestion::getCode)
                .isEqualTo(without.getSuggestions().stream().map(Suggestion::getCode).toList());

        // A plan again: the flag is all that changed, so the 2,000,000 a month is asked for as before.
        f.holdings.add(macbook);
        macbook.setWish(false);
        AdvisorResponse asPlan = advise();
        assertThat(asPlan.getSavingsThisMonth()).filteredOn(r -> "GOAL".equals(r.getBucket()))
                .extracting("refId", "target").containsExactly(tuple(40L, n("1000000")), tuple(41L, n("2000000")));
        assertThat(asPlan.getMeans().getGoals()).isEqualByComparingTo("3000000");
        assertThat(asPlan.getDaily().getBreakdown().getGoals())
                .isGreaterThan(withWish.getDaily().getBreakdown().getGoals());
        assertThat(asPlan.getDaily().getSafePerDay()).isLessThan(withWish.getDaily().getSafePerDay());
        assertThat(asPlan.getGoals()).extracting("id", "kind").containsExactly(tuple(40L, "PLAN"), tuple(41L, "PLAN"));
    }

    /** A goal with no monthly payment was never asked for; it is a wish by kind, and listed as one. */
    @Test
    void aGoalWithNoMonthlyPaymentIsAWishToo() {
        f.stableIncome("7000000", SEP.atDay(1));
        goal(42, "Umra", "30000000", null);

        AdvisorResponse r = advise();

        assertThat(r.getSavingsThisMonth()).noneMatch(row -> "GOAL".equals(row.getBucket()));
        assertThat(r.getMeans().getGoals()).isEqualByComparingTo("0");
        assertThat(r.getDaily().getBreakdown().getGoals()).isEqualByComparingTo("0");
        assertThat(r.getGoals()).extracting("id", "kind", "monthly", "status").containsExactly(tuple(42L, "WISH", null, null));
    }

    /** Spare money is offered to a plan first, then to a wish — never to a goal already reached. */
    @Test
    void spareMoneyGoesToAPlanFirstThenToAWish() {
        f.stableIncome("7000000", SEP.atDay(1));
        Category salary = f.category(10, "Salary", null);
        f.ledger.income(SEP.atDay(7), "7000000", salary);         // the salary is in: nothing still coming
        f.wallets.clear();
        f.wallet("CARD", Currency.UZS, "40000000");
        spent("1000000");
        Investment wish = goal(41, "MacBook", "10000000", "2000000");
        wish.setWish(true);
        Investment plan = goal(40, "Mashina", "20000000", "1000000");   // kept after the wish

        assertThat(extraToGoal(advise())).isEqualTo(40L);

        plan.setInvestedAmount(n("20000000"));                    // the plan is reached: the wish is next
        assertThat(extraToGoal(advise())).isEqualTo(41L);

        f.holdings.remove(plan);
        assertThat(extraToGoal(advise())).isEqualTo(41L);
    }

    private static Long extraToGoal(AdvisorResponse r) {
        return r.getSuggestions().stream().filter(s -> "advisor.s.extraToGoal".equals(s.getCode()))
                .map(Suggestion::getRefId).findFirst().orElse(null);
    }

    // ── means ─────────────────────────────────────────────────────────────────

    /** 7,000,000 − bills 3,000,000 − the rule's 30% (2,100,000) leaves 1,900,000 for goals. */
    private void aMonthWithRoomFor1900000() {
        f.stableIncome("7000000", SEP.atDay(1));
        f.bill(3, "Ijara", "3000000");
        f.ledger.billPaid(SEP.atDay(8), "3000000", 3);
    }

    @Test
    void means_fits_whenWhatIsLeftCoversThePace() {
        aMonthWithRoomFor1900000();
        goal(40, "Mashina", "20000000", "1000000");
        spent("400000");                                          // 20,000 a day → 600,000 a month

        Means m = advise().getMeans();

        assertThat(m.getIncome()).isEqualByComparingTo("7000000");
        assertThat(m.getBills()).isEqualByComparingTo("3000000");
        assertThat(m.getLoanPayments()).isEqualByComparingTo("0");
        assertThat(m.getSetAside()).isEqualByComparingTo("2100000");
        assertThat(m.getGoals()).isEqualByComparingTo("1000000");
        assertThat(m.getRoomForGoals()).isEqualByComparingTo("1900000");
        assertThat(m.getLeftToLive()).isEqualByComparingTo("900000");
        assertThat(m.getPaceMonthly()).isEqualByComparingTo("600000");
        assertThat(m.getVerdict()).isEqualTo("FITS");
    }

    @Test
    void means_tight_whenWhatIsLeftIsBelowThePace() {
        aMonthWithRoomFor1900000();
        goal(40, "Mashina", "20000000", "1000000");
        spent("1000000");                                         // 50,000 a day → 1,500,000 a month

        AdvisorResponse r = advise();

        assertThat(r.getMeans().getLeftToLive()).isEqualByComparingTo("900000");
        assertThat(r.getMeans().getPaceMonthly()).isEqualByComparingTo(r.getDaily().getPaceDaily().multiply(n("30")))
                .isEqualByComparingTo("1500000");
        assertThat(r.getMeans().getVerdict()).isEqualTo("TIGHT");
    }

    @Test
    void means_doesNotFit_whenThePlansAskMoreThanIsLeft_andLeftToLiveGoesNegative() {
        aMonthWithRoomFor1900000();
        goal(40, "Mashina", "20000000", "3000000");
        spent("400000");

        Means m = advise().getMeans();

        assertThat(m.getRoomForGoals()).isEqualByComparingTo("1900000");
        assertThat(m.getGoals()).isEqualByComparingTo("3000000");
        assertThat(m.getLeftToLive()).isEqualByComparingTo("-1100000");
        assertThat(m.getVerdict()).isEqualTo("DOES_NOT_FIT");
    }

    @Test
    void means_theVerdictsBoundaries() {
        assertThat(AdvisorService.meansVerdict(n("-1"), null)).isEqualTo("DOES_NOT_FIT");
        assertThat(AdvisorService.meansVerdict(n("-1"), n("0"))).isEqualTo("DOES_NOT_FIT");
        assertThat(AdvisorService.meansVerdict(n("0"), null)).isEqualTo("FITS");         // no pace yet: nothing to be tight against
        assertThat(AdvisorService.meansVerdict(n("900000"), n("900000"))).isEqualTo("FITS");   // not BELOW the pace
        assertThat(AdvisorService.meansVerdict(n("899999"), n("900000"))).isEqualTo("TIGHT");
        assertThat(AdvisorService.meansVerdict(n("0"), n("1"))).isEqualTo("TIGHT");
    }

    @Test
    void means_isNullWithoutAMonthlyIncome_andThereIsNoPaceInTheFirstWeek() {
        goal(40, "Mashina", "20000000", "1000000");
        AdvisorResponse noIncome = advise();
        assertThat(noIncome.getMeans()).isNull();
        assertThat(noIncome.getDaily()).isNull();
        assertThat(noIncome.getOwe()).isNotNull();                // never null
        assertThat(noIncome.getGoals()).extracting("id", "status").containsExactly(tuple(40L, "ON_TRACK"));

        f.stableIncome("7000000", SEP.atDay(1));
        AdvisorResponse firstWeek = f.advisor.advise(SEP.atDay(4));
        assertThat(firstWeek.getDaily().getPaceDaily()).isNull();
        assertThat(firstWeek.getMeans().getPaceMonthly()).isNull();
        assertThat(firstWeek.getMeans().getVerdict()).isEqualTo("FITS");   // 7M − 2.1M − 1M, and no pace to compare with
    }

    /**
     * "Next month" is what is counted: a loan plan and a goal that start in October are in, one that
     * starts in November is not; a plan nearly done asks only what finishes it; a wish asks nothing.
     */
    @Test
    void means_countsWhatNextMonthAsks() {
        f.stableIncome("7000000", SEP.atDay(1));
        f.bankLoan(1, "Xalq Banki", "Talim kredit", "26000000", "400000", LocalDate.of(2026, 1, 1), null);
        f.bankMark(1, SEP.atDay(1), "400000");
        f.borrowed(5, "Ota-onam", "50000000", "0", "500000", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 10, 1));
        goal(40, "Oktabrdan", "20000000", "1000000").setPaymentStartDate(LocalDate.of(2026, 10, 1));
        goal(41, "Noyabrdan", "20000000", "9000000").setPaymentStartDate(LocalDate.of(2026, 11, 1));
        Investment nearlyDone = goal(42, "Deyarli", "1000000", "800000");
        nearlyDone.setInvestedAmount(n("700000"));                // 300,000 finishes it
        goal(43, "Orzu", "5000000", "4000000").setWish(true);

        Means m = advise().getMeans();

        // October: the bank's 400,000 + the parents' 500,000. The plan is under 10% of the income, so
        // the rule stays the bank-loan one — and with over 5,000,000 left, its 7 / 3 / 10 %.
        assertThat(m.getLoanPayments()).isEqualByComparingTo("900000");
        assertThat(m.getSetAside()).isEqualByComparingTo("1400000");
        assertThat(m.getGoals()).isEqualByComparingTo("1300000");                  // 1,000,000 + 300,000
        assertThat(m.getRoomForGoals()).isEqualByComparingTo("4700000");
        assertThat(m.getLeftToLive()).isEqualByComparingTo("3400000");
    }

    // ── goals[].status ────────────────────────────────────────────────────────

    /** With room in the month: done, behind (and what would meet the deadline), on track; a wish has none. */
    @Test
    void goals_statusWhenTheMonthHasRoom() {
        f.stableIncome("14000000", SEP.atDay(1));                 // 30% set aside leaves 9,800,000
        Investment wish = goal(44, "Umra", "30000000", "5000000");
        wish.setWish(true);
        wish.setTargetDate(LocalDate.of(2026, 10, 31));           // a deadline it would miss — but a wish has no status
        Investment done = goal(40, "Telefon", "1000000", "100000");
        done.setInvestedAmount(n("1200000"));
        Investment behind = goal(41, "MacBook", "10000000", "1000000");
        behind.setTargetDate(LocalDate.of(2026, 11, 30));         // Sept, Oct, Nov: three payments for 10,000,000
        Investment onTrack = goal(42, "Velosiped", "3000000", "1000000");
        onTrack.setTargetDate(LocalDate.of(2026, 12, 31));        // reached in November
        onTrack.setPaymentStartDate(LocalDate.of(2026, 9, 1));
        goal(43, "Mashina", "90000000", "1000000");               // no deadline: nothing to be behind

        AdvisorResponse r = advise();

        assertThat(r.getMeans().getVerdict()).isNotEqualTo("DOES_NOT_FIT");
        // Plans first, then wishes — each group in the order the goals are kept.
        assertThat(r.getGoals()).extracting("id", "kind", "status", "neededMonthly").containsExactly(
                tuple(40L, "PLAN", "DONE", null),
                tuple(41L, "PLAN", "BEHIND", n("3340000")),       // 10,000,000 ÷ 3, up to the next 10,000
                tuple(42L, "PLAN", "ON_TRACK", null),
                tuple(43L, "PLAN", "ON_TRACK", null),
                tuple(44L, "WISH", null, null));
        Goal macbook = r.getGoals().get(1);
        assertThat(macbook.getName()).isEqualTo("MacBook");
        assertThat(macbook.getTarget()).isEqualByComparingTo("10000000");
        assertThat(macbook.getValue()).isEqualByComparingTo("0");
        assertThat(macbook.getMonthly()).isEqualByComparingTo("1000000");
        assertThat(macbook.getStartMonth()).isNull();              // never set
        assertThat(macbook.getDeadline()).isEqualTo("2026-11");
        assertThat(r.getGoals().get(2).getStartMonth()).isEqualTo("2026-09");
        assertThat(r.getGoals().get(0).getValue()).isEqualByComparingTo("1200000");
        assertThat(r.getGoals().get(3).getDeadline()).isNull();
    }

    /** The precedence: DONE, then DOES_NOT_FIT — before BEHIND and ON_TRACK alike. */
    @Test
    void goals_whenTheMonthDoesNotFitEveryUnfinishedPlanSaysSo() {
        f.stableIncome("7000000", SEP.atDay(1));
        Investment done = goal(40, "Telefon", "1000000", "100000");
        done.setInvestedAmount(n("1000000"));                     // exactly the target: done
        Investment behind = goal(41, "MacBook", "10000000", "3500000");
        behind.setTargetDate(LocalDate.of(2026, 10, 31));         // would be BEHIND
        goal(42, "Mashina", "90000000", "3000000");               // would be ON_TRACK
        goal(43, "Umra", null, null);

        AdvisorResponse r = advise();

        assertThat(r.getMeans().getGoals()).isEqualByComparingTo("6500000");       // the done plan asks nothing
        assertThat(r.getMeans().getVerdict()).isEqualTo("DOES_NOT_FIT");
        assertThat(r.getGoals()).extracting("id", "kind", "status", "neededMonthly", "target").containsExactly(
                tuple(40L, "PLAN", "DONE", null, n("1000000")),
                tuple(41L, "PLAN", "DOES_NOT_FIT", null, n("10000000")),
                tuple(42L, "PLAN", "DOES_NOT_FIT", null, n("90000000")),
                tuple(43L, "WISH", null, null, null));
    }

    @Test
    void goals_isEmptyWhenThereIsNone() {
        f.stableIncome("7000000", SEP.atDay(1));
        f.holding(1, "Aksiya", "1000000", null, MAY);             // an investment is not a goal

        assertThat(advise().getGoals()).isEmpty();
    }

    // ── owe ───────────────────────────────────────────────────────────────────

    @Test
    void owe_leavesOutALoanWhoseAmountIsUnknown_andSaysSo() {
        f.stableIncome("7000000", SEP.atDay(1));
        // Known: a monthly plan, ASAP money, a debt, and a bank loan with an end date (Oct–Dec left: September is marked).
        f.borrowed(1, "Akam", "3000000", "700000", "500000", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));
        f.borrowed(2, "Uzum Nasiya", "800000", "0", null, SEP.atDay(1), null);
        f.debt(3, "Do'kon", "600000", "100000", SEP.atDay(1));
        f.bankLoan(4, "Kapitalbank", "Avtokredit", "4800000", "400000", LocalDate.of(2026, 1, 15), LocalDate.of(2026, 12, 15));
        f.bankMark(4, SEP.atDay(1), "400000");
        // Unknown: a bank loan with no end date keeps no paid total.
        f.bankLoan(5, "Xalq Banki", "Talim kredit", "26000000", "400000", LocalDate.of(2026, 1, 25), null);
        f.borrowed(6, "Qaytarilgan", "900000", "900000", null, SEP.atDay(1), null);   // paid off: nowhere
        f.lent(1, "Do'stim", "1000000", "700000");

        AdvisorResponse r = advise();
        Owe owe = r.getOwe();

        assertThat(owe.getLeftToRepay()).isEqualByComparingTo("4800000");          // 2,300,000 + 800,000 + 500,000 + 1,200,000
        assertThat(owe.getNotCounted()).extracting("kind", "refId", "name")
                .containsExactly(tuple("BANK", 5L, "Xalq Banki · Talim kredit"));
        assertThat(owe.getToRepayFast()).isEqualByComparingTo("1300000");          // the ASAP loan and the debt only
        assertThat(owe.getOwedToYou()).isEqualByComparingTo("300000").isEqualByComparingTo(r.getOwedToYouTotal());

        // The same loans and the same `left` as Analytics' position.
        AnalyticsResponse.Position position = f.analytics.analytics(SEP, SEP, TODAY).getPosition();
        assertThat(position.getLoansLeft()).isEqualByComparingTo(owe.getLeftToRepay());
        assertThat(position.getOwedToYou()).isEqualByComparingTo(owe.getOwedToYou());
        assertThat(position.getLoans()).filteredOn(l -> l.getLeft() == null).extracting("kind", "refId", "name")
                .containsExactly(tuple("BANK", 5L, "Xalq Banki · Talim kredit"));
        assertThat(position.getLoans()).filteredOn(AnalyticsResponse.PositionLoan::isAsap)
                .extracting(AnalyticsResponse.PositionLoan::getLeft)
                .containsExactly(n("800000"), n("500000"));
    }

    @Test
    void owe_isZerosAndAnEmptyListWhenNothingIsOwed() {
        Owe owe = advise().getOwe();

        assertThat(owe.getLeftToRepay()).isEqualByComparingTo("0");
        assertThat(owe.getNotCounted()).isEmpty();
        assertThat(owe.getToRepayFast()).isEqualByComparingTo("0");
        assertThat(owe.getOwedToYou()).isEqualByComparingTo("0");
    }

    /** An installment paid does not change a loan whose amount is unknown into a known one. */
    @Test
    void owe_aBankLoanWithNoMonthlyPaymentIsNotCountedEither() {
        f.bankLoan(7, "Hamkor", "", "3000000", null, LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1));
        f.ledger.add(SEP.atDay(5), TransactionType.EXPENSE, TransactionSubType.BANK_LOAN_PAYMENT, "250000");

        Owe owe = advise().getOwe();

        assertThat(owe.getLeftToRepay()).isEqualByComparingTo("0");
        assertThat(owe.getNotCounted()).extracting("kind", "refId", "name").containsExactly(tuple("BANK", 7L, "Hamkor"));
    }

    // ── The wire ──────────────────────────────────────────────────────────────

    /** The new keys by the names the contract gives them — and every key that was there before, still there. */
    @Test
    void theJsonCarriesTheContractsFieldNames() {
        f.stableIncome("7000000", SEP.atDay(1));
        spent("1000000");
        goal(40, "Mashina", "20000000", "1000000").setTargetDate(LocalDate.of(2027, 6, 30));
        f.bankLoan(5, "Xalq Banki", "Talim kredit", "26000000", "400000", LocalDate.of(2026, 1, 25), null);
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();

        tools.jackson.databind.JsonNode json = mapper.valueToTree(advise());

        assertThat(json.propertyNames()).contains("means", "goals", "owe")
                .contains("date", "month", "suggestedSalaryMonth", "currency", "missingStableIncome", "have", "wallets",
                        "balanceCheckedOn", "balanceCheckedDaysAgo", "salaryExpected", "salaryReceived", "salaryComing",
                        "bonusReceived", "owedToYou", "owedToYouTotal", "bills", "billsLeft", "setAside", "setAsideLeft",
                        "setAsideAfterBills", "free", "suggestions", "daily", "savingsThisMonth")
                .hasSize(27);
        assertThat(json.get("daily").propertyNames()).containsExactlyInAnyOrder("safePerDay", "until", "tightestOn",
                "breakdown", "paceDaily", "paceFrom", "paceTo", "runsOutOn", "shortBy", "upcoming", "incomes",
                "verdict", "cause", "safePerDayNoGoals", "safePerDayNoSavings");
        assertThat(json.get("daily").get("breakdown").propertyNames()).containsExactlyInAnyOrder(
                "have", "comingIn", "goingOut", "savings", "net", "days", "setAside", "goals");
        assertThat(json.get("means").propertyNames()).containsExactlyInAnyOrder("income", "bills", "loanPayments",
                "setAside", "goals", "leftToLive", "roomForGoals", "paceMonthly", "verdict");
        assertThat(json.get("goals").get(0).propertyNames()).containsExactlyInAnyOrder("id", "name", "kind", "target",
                "value", "monthly", "startMonth", "deadline", "status", "neededMonthly");
        assertThat(json.get("goals").get(0).get("deadline").asString()).isEqualTo("2027-06");
        assertThat(json.get("goals").get(0).get("startMonth").isNull()).isTrue();
        assertThat(json.get("owe").propertyNames()).containsExactlyInAnyOrder(
                "leftToRepay", "notCounted", "toRepayFast", "owedToYou");
        assertThat(json.get("owe").get("notCounted").get(0).propertyNames()).containsExactlyInAnyOrder("kind", "refId", "name");

        tools.jackson.databind.JsonNode holding = mapper.valueToTree(
                uz.tracker.trackerproject.dto.response.InvestmentResponse.from(f.holdings.get(0)));
        assertThat(holding.get("wish").isBoolean()).isTrue();
        assertThat(holding.get("goalKind").asString()).isEqualTo("PLAN");
        tools.jackson.databind.JsonNode row = mapper.valueToTree(
                uz.tracker.trackerproject.dto.response.TransactionResponse.from(f.spend(SEP.atDay(11), "5000", null, "Non")));
        assertThat(row.get("flow").asString()).isEqualTo("EVERYDAY");
    }
}
