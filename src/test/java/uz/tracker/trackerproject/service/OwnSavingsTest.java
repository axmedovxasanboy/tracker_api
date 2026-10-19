package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uz.tracker.trackerproject.dto.request.CustomSavingRequest;
import uz.tracker.trackerproject.dto.request.LevelRulesRequest;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.SavingsRow;
import uz.tracker.trackerproject.dto.response.LevelsResponse;
import uz.tracker.trackerproject.dto.response.ProfileResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LevelRuleVersion;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The owner's own savings (2026-10-19): "There are only 3 savings and they are all built in. I want to
 * add additional savings for those 3 allocations. And that will be done through saving rules." Each
 * one asks its own percentage on top of the three, per level and situation, from a month on; its money
 * goes into a pot of its own and is never counted as Investments; what a month leaves unpaid carries.
 * The owner's case: 10M income, no loans (10% · 5% · 15%), a "Car" saving at 3%.
 */
class OwnSavingsTest {

    private final YearMonth now = OverviewService.ownerMonth();
    private final LocalDate today = LocalDate.now(LevelScheduler.OWNER_ZONE);
    private LevelsFixture fx;
    private TransactionRepository moved;
    private long nextId = 500;

    @BeforeEach
    void setUp() {
        fx = new LevelsFixture();
        fx.incomeFrom(now.minusMonths(2), "10000000");

        InvestmentRepository pots = mock(InvestmentRepository.class);
        when(pots.save(any(Investment.class))).thenAnswer(i -> {
            Investment s = i.getArgument(0);
            if (s.getId() == null) s.setId(nextId++);
            if (!fx.f.holdings.contains(s)) fx.f.holdings.add(s);
            return s;
        });
        when(pots.findById(any())).thenAnswer(i -> fx.f.holdings.stream()
                .filter(s -> s.getId().equals(i.getArgument(0))).findFirst());
        doAnswer(i -> fx.f.holdings.remove(i.<Investment>getArgument(0))).when(pots).delete(any(Investment.class));
        moved = mock(TransactionRepository.class);
        ReflectionTestUtils.setField(fx.levels, "investmentRepository", pots);
        ReflectionTestUtils.setField(fx.levels, "transactionRepository", moved);
    }

    private static CustomSavingRequest saving(String name, String percent, YearMonth from) {
        CustomSavingRequest r = new CustomSavingRequest();
        r.setName(name);
        r.setPercent(percent == null ? null : new BigDecimal(percent));
        r.setFrom(from == null ? null : from.toString());
        return r;
    }

    private Long car(YearMonth from) {
        LevelsResponse r = fx.levels.createSaving(saving("Car", "3", from), today);
        return r.getCustomSavings().getFirst().getId();
    }

    private List<AllocationLine> lines(YearMonth month) {
        return fx.f.overview.getTierIgnoringSubscriptions(month, Currency.UZS, month.atDay(15)).getAllocation().getLines();
    }

    private AllocationLine own(YearMonth month, Long id) {
        return lines(month).stream().filter(l -> "CUSTOM".equals(l.getBucket()) && id.equals(l.getRefId()))
                .findFirst().orElseThrow();
    }

    private Transaction putIn(LocalDate on, Long id, String amount) {
        Transaction t = fx.f.ledger.add(on, TransactionType.EXPENSE, TransactionSubType.INVESTMENT, amount);
        t.setInvestmentId(id);
        t.setAllocationBucket(AllocationBucket.CUSTOM);
        return t;
    }

    @Test
    void aNewSavingAsksItsPercentageOnTopOfTheThreeFromItsMonth() {
        Long id = car(now);

        AllocationLine line = own(now, id);
        assertThat(line.getLabel()).isEqualTo("Car");
        assertThat(line.isRecommended()).isTrue();
        assertThat(line.getMinPercent()).isEqualByComparingTo("3");
        assertThat(line.getMinAmount()).isEqualByComparingTo("300000");
        // The three are untouched.
        assertThat(lines(now)).filteredOn(l -> !"CUSTOM".equals(l.getBucket()))
                .extracting(AllocationLine::getMinPercent).containsExactly(new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("15"));
        // The month before it began asks nothing of it.
        assertThat(own(now.minusMonths(1), id).isRecommended()).isFalse();

        // Every level, every situation, from that month: a column of its own on Savings rules.
        LevelsResponse r = fx.levels.levels(today);
        assertThat(r.getCustomSavings()).extracting(LevelsResponse.CustomSaving::getName).containsExactly("Car");
        assertThat(r.getPercents().getCustom()).containsEntry(id, new BigDecimal("3"));
        for (LevelsResponse.Level level : r.getLevels()) {
            LevelsResponse.Version first = level.getVersions().getFirst();
            LevelsResponse.Version last = level.getVersions().getLast();
            assertThat(last.getFrom()).isEqualTo(now.toString());
            assertThat(last.getRules().values()).allSatisfy(p -> assertThat(p.getCustom().get(id)).isEqualByComparingTo("3"));
            assertThat(first.getRules().values()).allSatisfy(p -> assertThat(p.getCustom().get(id)).isEqualByComparingTo("0"));
        }
    }

    @Test
    void itsMoneyIsItsOwnNeverInvestments() {
        Long id = car(now);
        putIn(now.atDay(3), id, "100000");

        assertThat(own(now, id).getPaidAmount()).isEqualByComparingTo("100000");
        assertThat(own(now, id).getRemainingAmount()).isEqualByComparingTo("200000");
        assertThat(lines(now)).filteredOn(l -> "INVESTMENTS".equals(l.getBucket()))
                .singleElement().satisfies(l -> assertThat(l.getPaidAmount()).isEqualByComparingTo("0"));
        // Home's daily figure keeps it back like the three: 33% of the month is asked.
        assertThat(fx.f.advisor.savingsThisMonth(today)).filteredOn(r -> "CUSTOM".equals(r.getBucket()))
                .singleElement().satisfies(r -> {
                    assertThat(r.getRefId()).isEqualTo(id);
                    assertThat(r.getName()).isEqualTo("Car");
                    assertThat(r.getRemaining()).isEqualByComparingTo("200000");
                });
    }

    @Test
    void whatAMonthLeavesUnpaidCarriesLikeTheThree() {
        Long id = car(now.minusMonths(1));
        // Last month asked 300k, 100k went in: 200k carries into this one.
        putIn(now.minusMonths(1).atDay(10), id, "100000");

        assertThat(fx.f.overview.carriedInto(now).get(OverviewService.customKey(id))).isEqualByComparingTo("200000");
        SavingsRow row = fx.f.advisor.savingsThisMonth(today).stream()
                .filter(r -> "CUSTOM".equals(r.getBucket())).findFirst().orElseThrow();
        assertThat(row.getCarried()).isEqualByComparingTo("200000");
        assertThat(row.getRemaining()).isEqualByComparingTo("500000");

        // Profile lists it after the three, its carry beside it; the totals count it.
        ProfileService profile = new ProfileService(fx.f.overview,
                (TransactionRepository) ReflectionTestUtils.getField(fx.f.overview, "transactionRepository"));
        ProfileResponse p = profile.profile(today, "owner");
        assertThat(p.getBuckets()).extracting(ProfileResponse.Bucket::getBucket)
                .containsExactly("DONATION", "EMERGENCY", "INVESTMENTS", "CUSTOM");
        assertThat(p.getBuckets().getLast()).satisfies(b -> {
            assertThat(b.getName()).isEqualTo("Car");
            assertThat(b.getPercent()).isEqualByComparingTo("3");
            assertThat(b.getCarried()).isEqualByComparingTo("200000");
        });
        assertThat(p.getTotalPercent()).isEqualByComparingTo("33");
    }

    @Test
    void eachPercentageChangesOnItsOwnAndAllTogetherStayWithin100() {
        Long id = car(now);
        LevelRulesRequest req = new LevelRulesRequest();
        LevelRulesRequest.Percents noLoans = new LevelRulesRequest.Percents();
        noLoans.setCustom(Map.of(id, new BigDecimal("6")));
        req.setRules(Map.of(SavingsRules.NO_DEBT, noLoans));
        fx.levels.saveRules("1", req, today);

        assertThat(own(now, id).getMinPercent()).isEqualByComparingTo("6");
        // The three stayed as they were.
        assertThat(lines(now).getFirst().getMinPercent()).isEqualByComparingTo("10");

        noLoans.setCustom(Map.of(id, new BigDecimal("71")));
        assertThatThrownBy(() -> fx.levels.saveRules("1", req, today))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 100%");
        assertThatThrownBy(() -> fx.levels.createSaving(saving("Hajj", "71", now), today))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 100%");
        assertThat(fx.f.holdings).extracting(Investment::getName).containsExactly("Car");
    }

    @Test
    void stoppedFromAMonthItAsksNothingMoreAndTheMonthsBeforeKeepTheirAsk() {
        Long id = car(now);
        fx.levels.stopSaving(id, saving(null, null, now.plusMonths(1)), today);

        assertThat(own(now, id).getMinPercent()).isEqualByComparingTo("3");
        assertThat(own(now.plusMonths(1), id).isRecommended()).isFalse();
    }

    @Test
    void deletedOnlyWhileNoMoneyEverMovedThroughIt() {
        Long id = car(now);
        Investment pot = fx.f.holdings.getFirst();
        pot.setInvestedAmount(new BigDecimal("100000"));
        assertThatThrownBy(() -> fx.levels.deleteSaving(id, today))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stop it");

        pot.setInvestedAmount(BigDecimal.ZERO);
        LevelsResponse r = fx.levels.deleteSaving(id, today);
        assertThat(r.getCustomSavings()).isEmpty();
        assertThat(fx.f.holdings).isEmpty();
        assertThat(fx.versions).allSatisfy(v -> assertThat(v.getCustomPercents()).isEmpty());
        assertThat(lines(now)).noneMatch(l -> "CUSTOM".equals(l.getBucket()));
    }

    @Test
    void namesAreNeededAndNeverTwice() {
        car(now);
        assertThatThrownBy(() -> fx.levels.createSaving(saving("  car ", "2", now), today))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already");
        assertThatThrownBy(() -> fx.levels.createSaving(saving(" ", "2", now), today))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
    }

    @Test
    void aNewVersionOfALevelKeepsTheOwnSavingsPercentages() {
        Long id = car(now.minusMonths(1));
        // A change to the three from this month copies the version in force — the saving's 3% too.
        LevelRulesRequest req = new LevelRulesRequest();
        LevelRulesRequest.Percents noLoans = new LevelRulesRequest.Percents();
        noLoans.setDonation(new BigDecimal("8"));
        req.setRules(Map.of(SavingsRules.NO_DEBT, noLoans));
        fx.levels.saveRules("1", req, today);

        LevelRuleVersion v = fx.versions.stream()
                .filter(x -> x.getLevel() == 1 && x.getFromMonth().equals(now.atDay(1))).findFirst().orElseThrow();
        assertThat(v.getCustomPercents()).containsEntry(LevelRuleVersion.cell(SavingsRules.NO_DEBT, id), new BigDecimal("3"));
        assertThat(own(now, id).getMinPercent()).isEqualByComparingTo("3");
        assertThat(lines(now).getFirst().getMinPercent()).isEqualByComparingTo("8");
    }

    @Test
    void analyticsCountsItsMoneyApartFromInvestments() {
        Long id = car(now);
        Transaction t = putIn(now.atDay(3), id, "100000");
        assertThat(AnalyticsService.savedKind(t, fx.f.overview)).isEqualTo(AnalyticsService.CUSTOM);
        assertThat(HoldingLinks.kindOf(fx.f.holdings.getFirst())).isEqualTo("CUSTOM");
    }
}
