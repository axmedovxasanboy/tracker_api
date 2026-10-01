package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AdvisorResponse;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.StableIncomeEntry;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.when;

/**
 * The monthly income is a value per month (STABLE-INCOME-HISTORY.md): a change applies from the
 * month the owner picks until the next change already recorded, and months before it never move —
 * not their level, their rule, their targets, nor what they carried. Everything real runs over one
 * set of rows: the Plan, the ledger, the daily walk, the advisor and Analytics.
 */
class StableIncomeHistoryEngineTest {

    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);

    private AnalyticsFixture f;
    private final List<StableIncomeEntry> history = new ArrayList<>();

    @BeforeEach
    void setUp() {
        f = new AnalyticsFixture();
        when(f.settingsService.stableIncomeSchedule()).thenAnswer(inv ->
                StableIncomeSchedule.of(history, f.settings.getMonthlyStableIncome()));
    }

    /** {@code amount} from {@code month} on — what PUT /settings with stableIncomeFrom records. */
    private void incomeFrom(YearMonth month, String amount) {
        history.removeIf(e -> e.getMonth().equals(month.atDay(1)));
        history.add(new StableIncomeEntry(month.atDay(1), new BigDecimal(amount)));
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    /** What a month's rule and targets are, as plain text so two readings can be compared exactly. */
    private String tier(YearMonth month) {
        OverviewTierResponse t = f.overview.getTierIgnoringSubscriptions(month, Currency.UZS, month.atDay(20));
        StringBuilder sb = new StringBuilder(t.getIncome().toPlainString()).append(" L").append(t.getLevel())
                .append(" ").append(t.getSubLevel()).append(" base ").append(t.getAllocationBase().toPlainString());
        for (TierAllocation.AllocationLine l : t.getAllocation().getLines()) {
            sb.append(" | ").append(l.getBucket()).append(" ").append(l.getMinPercent()).append("% ")
                    .append(l.getMinAmount() == null ? "-" : l.getMinAmount().stripTrailingZeros().toPlainString());
        }
        return sb.toString();
    }

    private String ledger(YearMonth month) {
        AllocationLedgerResponse r = f.overview.getAllocationLedger(month, Currency.UZS);
        StringBuilder sb = new StringBuilder(r.getStableIncome().toPlainString()).append(" L").append(r.getLevel());
        for (AllocationLedgerResponse.BucketLedger b : r.getBuckets()) {
            sb.append(" | ").append(b.getBucket()).append(" ").append(plain(b.getRecommended())).append(" carried ")
                    .append(plain(b.getCarried())).append(" owed ").append(plain(b.getOutstanding()));
        }
        for (AllocationLedgerResponse.MonthBreakdown m : r.getMonths()) {
            sb.append(" || ").append(m.getMonth()).append(" ").append(plain(m.getStableIncome())).append(" L").append(m.getLevel());
            m.getLines().forEach(l -> sb.append(" ").append(l.getBucket()).append(" ").append(plain(l.getRecommended())));
        }
        return sb.toString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    /** 7,000,000 from August; nothing but investments paid: 400,000 in August, 500,000 in September. */
    private void augustOn7Million() {
        f.stableIncome("7000000", AUG.atDay(1));
        incomeFrom(AUG, "7000000");
        f.save(AUG.atDay(10), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "400000");
        f.save(SEP.atDay(10), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "500000");
    }

    @Test
    void aChangeFromOctoberLeavesSeptemberExactlyAsItWas() {
        augustOn7Million();
        String septemberTier = tier(SEP);
        String septemberLedger = ledger(SEP);
        Map<String, BigDecimal> carriedIntoOctober = f.overview.carriedInto(OCT);
        assertThat(septemberTier).startsWith("7000000 L1 1.1");

        incomeFrom(OCT, "20000000");

        // September: the same level, rule, base and targets; the same ledger and the same carry.
        assertThat(tier(SEP)).isEqualTo(septemberTier);
        assertThat(ledger(SEP)).isEqualTo(septemberLedger);
        assertThat(f.overview.carriedInto(OCT)).isEqualTo(carriedIntoOctober);
        // October is on its own income: 20,000,000 is Level 2.
        OverviewTierResponse october = f.overview.getTierIgnoringSubscriptions(OCT, Currency.UZS, OCT.atDay(20));
        assertThat(october.getIncome()).isEqualByComparingTo("20000000");
        assertThat(october.getLevel()).isEqualTo(2);
    }

    @Test
    void aChangeFromSeptemberWithAnOctoberEntryPresentChangesSeptemberOnly() {
        augustOn7Million();
        incomeFrom(OCT, "9000000");
        String august = tier(AUG);
        String october = tier(OCT);

        incomeFrom(SEP, "8000000");

        assertThat(tier(AUG)).isEqualTo(august);
        assertThat(tier(OCT)).isEqualTo(october);
        assertThat(tier(SEP)).startsWith("8000000 L1 1.1 base 8000000")
                .contains("INVESTMENTS 15% 1200000");
    }

    /** The ledger rebuilds each month on that month's own income — and carries what each left unpaid. */
    @Test
    void theLedgerMonthByMonthWithTwoEntries() {
        augustOn7Million();
        incomeFrom(SEP, "10000000");

        AllocationLedgerResponse r = f.overview.getAllocationLedger(SEP, Currency.UZS);

        assertThat(r.getMonths()).extracting(m -> m.getMonth(), m -> plain(m.getStableIncome()), m -> m.getLevel(),
                        m -> plain(m.getAllocationBase()))
                .containsExactly(tuple("2026-08", "7000000", 1, "7000000"), tuple("2026-09", "10000000", 1, "10000000"));
        assertThat(r.getMonths().get(0).getLines()).extracting(l -> l.getBucket(), l -> plain(l.getRecommended()))
                .containsExactly(tuple("DONATION", "700000"), tuple("EMERGENCY", "350000"), tuple("INVESTMENTS", "1050000"));
        assertThat(r.getMonths().get(1).getLines()).extracting(l -> l.getBucket(), l -> plain(l.getRecommended()))
                .containsExactly(tuple("DONATION", "1000000"), tuple("EMERGENCY", "500000"), tuple("INVESTMENTS", "1500000"));
        // September's own figures, with what August left unpaid on its 7,000,000 carried in.
        assertThat(r.getStableIncome()).isEqualByComparingTo("10000000");
        assertThat(r.getBuckets()).extracting(b -> b.getBucket(), b -> plain(b.getRecommended()), b -> plain(b.getCarried()),
                        b -> plain(b.getOutstanding()))
                .containsExactly(
                        tuple("DONATION", "1000000", "700000", "1700000"),
                        tuple("EMERGENCY", "500000", "350000", "850000"),
                        tuple("INVESTMENTS", "1500000", "650000", "1650000"));
    }

    // ── The advisor and Analytics ─────────────────────────────────────────────

    /** 7,000,000 a month, paid on the 7th and in for September; a raise to 9,000,000 recorded from October. */
    private AdvisorResponse adviseWithRaise(boolean raise) {
        f.stableIncome("7000000", SEP.atDay(1));
        history.clear();
        incomeFrom(SEP, "7000000");
        if (raise) incomeFrom(OCT, "9000000");
        if (f.wallets.isEmpty()) {
            Category salary = f.category(10, "Salary", null);
            f.ledger.income(SEP.atDay(7), "7000000", salary);
            f.wallet("CARD", Currency.UZS, "9000000");
        }
        return f.advisor.advise(SEP.atDay(23));
    }

    /**
     * The walk takes October on October's income: the salary on 7 October is 9,000,000 (the same shape,
     * the month's own size), and October's rule sets aside 30% of it — 2,700,000, not 2,100,000.
     */
    @Test
    void theDailyWalkUsesAFutureMonthsRaiseInThatMonth() {
        AdvisorResponse before = adviseWithRaise(false);
        assertThat(before.getDaily().getIncomes()).extracting(i -> i.getDate(), i -> plain(i.getAmount()))
                .containsExactly(tuple(LocalDate.of(2026, 10, 7), "7000000"));
        assertThat(before.getDaily().getBreakdown().getSetAside()).isEqualByComparingTo("4200000");   // 2,100,000 now + October's

        AdvisorResponse after = adviseWithRaise(true);

        assertThat(after.getSalaryExpected()).isEqualByComparingTo("7000000");                        // September is September's
        assertThat(after.getDaily().getIncomes()).extracting(i -> i.getDate(), i -> plain(i.getAmount()))
                .containsExactly(tuple(LocalDate.of(2026, 10, 7), "9000000"));
        assertThat(after.getDaily().getTightestOn()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(after.getDaily().getBreakdown().getSetAside()).isEqualByComparingTo("4800000");    // 2,100,000 + 2,700,000
    }

    @Test
    void meansUsesNextMonthsIncome() {
        AdvisorResponse r = adviseWithRaise(true);

        assertThat(r.getMeans().getIncome()).isEqualByComparingTo("9000000");
        assertThat(r.getMeans().getSetAside()).isEqualByComparingTo("2700000");
        assertThat(r.getMeans().getRoomForGoals()).isEqualByComparingTo("6300000");
    }

    @Test
    void analyticsStableIncomeIsTheIncomeOfTheRangesLastMonth() {
        f.stableIncome("7000000", AUG.atDay(1));
        incomeFrom(AUG, "6000000");
        incomeFrom(SEP, "7000000");

        assertThat(f.analytics.analytics(AUG, AUG, SEP.atDay(20)).getStableIncome()).isEqualByComparingTo("6000000");
        assertThat(f.analytics.analytics(AUG, SEP, SEP.atDay(20)).getStableIncome()).isEqualByComparingTo("7000000");
        assertThat(f.analytics.analytics(YearMonth.of(2026, 7), YearMonth.of(2026, 7), SEP.atDay(20)).getStableIncome())
                .isEqualByComparingTo("6000000");                                                     // before the first: the first
    }

    /** With one entry every month reads what the single value gave — the backfilled history changes nothing. */
    @Test
    void withOneEntryEveryMonthReadsWhatItReadBefore() {
        augustOn7Million();
        String withHistory = tier(SEP) + " / " + ledger(SEP);

        history.clear();                                                                             // the single value alone
        assertThat(tier(SEP) + " / " + ledger(SEP)).isEqualTo(withHistory);
    }
}
