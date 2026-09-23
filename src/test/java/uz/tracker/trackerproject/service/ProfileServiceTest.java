package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.ProfileResponse;
import uz.tracker.trackerproject.dto.response.ProfileResponse.AllocatedLine;
import uz.tracker.trackerproject.dto.response.ProfileResponse.Bucket;
import uz.tracker.trackerproject.dto.response.ProfileResponse.IncomeLine;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.LevelAllocationRule;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The profile over the real tier engine (mocked repositories): the level is the 15M step alone,
 * the rule is named after the scenario the engine picked, and a bucket the rule does not ask for
 * stays in the list at 0. The owner's own case is in AdvisorOwnerSeptemberTest.
 */
class ProfileServiceTest {

    private static final LocalDate SEP_23 = LocalDate.of(2026, 9, 23);

    private Settings settings;
    private List<MonthlyPayment> bills;
    private LevelAllocationRuleRepository ruleRepository;
    private OverviewService overview;
    private ProfileService service;
    private TransactionLedger ledger;

    @BeforeEach
    void setUp() {
        SettingsService settingsService = mock(SettingsService.class);
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        ruleRepository = mock(LevelAllocationRuleRepository.class);

        settings = new Settings();
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 9, 1));
        when(settingsService.getOrCreate()).thenReturn(settings);
        bills = new ArrayList<>();
        when(monthlyPaymentRepository.findAll()).thenReturn(bills);

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        ledger = new TransactionLedger(transactionRepository);
        overview = new OverviewService(transactionRepository, monthlyPaymentRepository,
                mock(BankLoanRepository.class), mock(LoanTakenRepository.class), mock(DebtRepository.class),
                mock(DonationRepository.class), mock(InvestmentRepository.class), ruleRepository,
                mock(LevelConfigRepository.class), mock(MarkPaidRepository.class), settingsService, mock(CategoryRepository.class));
        service = new ProfileService(overview, transactionRepository);
    }

    private void income(String amount) {
        settings.setMonthlyStableIncome(new BigDecimal(amount));
    }

    private static LevelAllocationRule rule(String subLevel, String donation, String emergency, String investments) {
        LevelAllocationRule r = new LevelAllocationRule();
        r.setSubLevel(subLevel);
        r.setLevel(Integer.parseInt(subLevel.substring(0, 1)));
        r.setDonationPercent(donation == null ? null : new BigDecimal(donation));
        r.setEmergencyPercent(emergency == null ? null : new BigDecimal(emergency));
        r.setInvestmentsPercent(investments == null ? null : new BigDecimal(investments));
        return r;
    }

    @Test
    void withoutAStableIncomeThereIsNoLevelRuleOrBuckets() {
        ProfileResponse p = service.profile(SEP_23, "owner");

        assertThat(p.isMissingStableIncome()).isTrue();
        assertThat(p.getUsername()).isEqualTo("owner");
        assertThat(p.getMonth()).isEqualTo("2026-09");
        assertThat(p.getLevel()).isNull();
        assertThat(p.getRule()).isNull();
        assertThat(p.getBuckets()).isEmpty();
        assertThat(p.getNextMonth()).isNull();
    }

    /** Income and what was set aside are facts, so they show before a stable income is set — without targets. */
    @Test
    void withoutAStableIncomeTheMonthsMoneyStillShowsWithoutTargets() {
        Category salary = category(1L, "Salary", null);
        ledger.income(LocalDate.of(2026, 9, 7), "7000000", salary);
        ledger.add(LocalDate.of(2026, 9, 10), TransactionType.EXPENSE, TransactionSubType.EMERGENCY_CONTRIBUTION, "200000");

        ProfileResponse p = service.profile(SEP_23, "owner");

        assertThat(p.getIncomeThisMonth().getTotal()).isEqualByComparingTo("7000000");
        assertThat(p.getAllocatedThisMonth().getLines())
                .extracting(AllocatedLine::getBucket, a -> a.getAmount().toPlainString(), AllocatedLine::getTarget)
                .containsExactly(tuple("DONATION", "0", null), tuple("EMERGENCY", "200000", null),
                        tuple("INVESTMENTS", "0", null));
    }

    private static Category category(Long id, String name, Category parent) {
        Category c = TransactionLedger.category(name, false, parent);
        c.setId(id);
        return c;
    }

    /**
     * Earned money only, by the category it was recorded in, dated today or earlier: the salary's
     * two payments are one line and its advance another; borrowed money and a loan paid back are
     * named beside; a transfer, a check-in's surplus, a payment dated later and a dollar pot are not
     * income at all.
     */
    @Test
    void incomeIsEarnedMoneyByLeafCategoryDatedByToday() {
        income("7000000");
        Category salary = category(1L, "Salary", null);
        Category avans = category(2L, "Avans", salary);
        Category freelance = category(3L, "Freelance", null);
        freelance.setNameUz("Frilans");
        ledger.income(LocalDate.of(2026, 9, 7), "5000000", salary);
        ledger.income(LocalDate.of(2026, 9, 20), "1000000", salary);
        ledger.income(LocalDate.of(2026, 9, 15), "2000000", avans);
        ledger.income(LocalDate.of(2026, 9, 10), "500000", freelance);
        ledger.income(LocalDate.of(2026, 9, 12), "50000", null);
        ledger.add(LocalDate.of(2026, 9, 14), TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "1000000");
        ledger.add(LocalDate.of(2026, 9, 16), TransactionType.INCOME, TransactionSubType.LOAN_RETURNED_TO_ME, "300000");
        ledger.add(LocalDate.of(2026, 9, 15), TransactionType.INCOME, TransactionSubType.TRANSFER_IN, "400000")
                .setTransferPairId(99L);
        ledger.add(LocalDate.of(2026, 9, 22), TransactionType.INCOME, TransactionSubType.EVERYDAY_SPENDING, "120000");
        ledger.income(LocalDate.of(2026, 9, 28), "800000", salary);                    // dated after today
        ledger.income(LocalDate.of(2026, 9, 18), "100", salary).setCurrency(Currency.USD); // a dollar pot

        ProfileResponse.IncomeThisMonth in = service.profile(SEP_23, "owner").getIncomeThisMonth();

        assertThat(in.getLines())
                .extracting(IncomeLine::getCategoryId, IncomeLine::getName, IncomeLine::getNameUz,
                        l -> l.getAmount().toPlainString())
                .containsExactly(
                        tuple(1L, "Salary", null, "6000000"),
                        tuple(2L, "Avans", null, "2000000"),
                        tuple(3L, "Freelance", "Frilans", "500000"),
                        tuple(null, "Uncategorized", null, "50000"));
        assertThat(in.getTotal()).isEqualByComparingTo("8550000");
        assertThat(in.getExcludedBorrowed()).isEqualByComparingTo("1000000");
        assertThat(in.getExcludedReturned()).isEqualByComparingTo("300000");
    }

    /**
     * What was set aside, as a share of the income: each bucket against its rule amount, then the
     * savings goals — by today (the goal payment dated the 28th is not in yet).
     */
    @Test
    void setAsideIsEachBucketThenTheGoalsAsAShareOfTheIncome() {
        income("7000000");
        ledger.income(LocalDate.of(2026, 9, 7), "7000000", category(1L, "Salary", null));
        ledger.add(LocalDate.of(2026, 9, 12), TransactionType.EXPENSE, TransactionSubType.EMERGENCY_CONTRIBUTION, "200000");
        ledger.add(LocalDate.of(2026, 9, 11), TransactionType.EXPENSE, TransactionSubType.INVESTMENT, "300000")
                .setAllocationBucket(AllocationBucket.INVESTMENTS);
        ledger.add(LocalDate.of(2026, 9, 10), TransactionType.EXPENSE, TransactionSubType.INVESTMENT, "400000")
                .setAllocationBucket(AllocationBucket.SAVINGS);
        ledger.add(LocalDate.of(2026, 9, 28), TransactionType.EXPENSE, TransactionSubType.INVESTMENT, "100000")
                .setAllocationBucket(AllocationBucket.SAVINGS);

        ProfileResponse.AllocatedThisMonth set = service.profile(SEP_23, "owner").getAllocatedThisMonth();

        // Level 1 with no debt: 10 / 5 / 15 % of 7M.
        assertThat(set.getLines())
                .extracting(AllocatedLine::getBucket, a -> a.getAmount().toPlainString(),
                        a -> a.getPercentOfIncome().toPlainString(),
                        a -> a.getTarget() == null ? null : a.getTarget().stripTrailingZeros().toPlainString())
                .containsExactly(
                        tuple("DONATION", "0", "0.0", "700000"),
                        tuple("EMERGENCY", "200000", "2.9", "350000"),
                        tuple("INVESTMENTS", "300000", "4.3", "1050000"),
                        tuple("GOALS", "400000", "5.7", null));
        assertThat(set.getTotal()).isEqualByComparingTo("900000");
        assertThat(set.getPercentOfIncome()).isEqualByComparingTo("12.9");
    }

    @Test
    void withNoIncomeYetThereAreNoPercentages() {
        income("7000000");
        ledger.add(LocalDate.of(2026, 9, 12), TransactionType.EXPENSE, TransactionSubType.EMERGENCY_CONTRIBUTION, "200000");

        ProfileResponse p = service.profile(SEP_23, "owner");

        assertThat(p.getIncomeThisMonth().getTotal()).isEqualByComparingTo("0");
        assertThat(p.getIncomeThisMonth().getLines()).isEmpty();
        assertThat(p.getAllocatedThisMonth().getTotal()).isEqualByComparingTo("200000");
        assertThat(p.getAllocatedThisMonth().getPercentOfIncome()).isNull();
        assertThat(p.getAllocatedThisMonth().getLines()).allSatisfy(a -> assertThat(a.getPercentOfIncome()).isNull());
        assertThat(p.getAllocatedThisMonth().getLines()).extracting(AllocatedLine::getBucket)
                .containsExactly("DONATION", "EMERGENCY", "INVESTMENTS");   // no goal money: no GOALS line
    }

    /** 25M left after bills is Level 2; with no debt its sub-level is 2.1 and the owner's rule for it applies. */
    @Test
    void levelTwoUsesTheOwnersOwnRuleForItsSubLevel() {
        income("25000000");
        when(ruleRepository.findBySubLevel("2.1")).thenReturn(Optional.of(rule("2.1", "8", "4", "12")));

        ProfileResponse p = service.profile(SEP_23, "owner");

        assertThat(p.getLevel()).isEqualTo(2);
        assertThat(p.getLevelFrom()).isEqualByComparingTo("15000000");
        assertThat(p.getNextLevelAt()).isEqualByComparingTo("30000000");
        assertThat(p.getRule().getReason()).isEqualTo("CUSTOM");
        assertThat(p.getRule().getCutoff()).isNull();
        assertThat(p.getBuckets())
                .extracting(Bucket::getBucket, b -> b.getPercent().toPlainString(),
                        b -> b.getAmount().stripTrailingZeros().toPlainString(),
                        b -> b.getNormalMonthAmount().stripTrailingZeros().toPlainString())
                .containsExactly(
                        tuple("DONATION", "8", "2000000", "2000000"),
                        tuple("EMERGENCY", "4", "1000000", "1000000"),
                        tuple("INVESTMENTS", "12", "3000000", "3000000"));
        assertThat(p.getTotalPercent()).isEqualByComparingTo("24");
        // Nothing starts or ends next month: the same rule and percentages, so nothing to announce.
        assertThat(p.getNextMonth()).isNull();
    }

    /** A Level 2 sub-level nobody has set percentages for yet: named, and every bucket stays at 0. */
    @Test
    void aSubLevelWithoutAConfiguredRuleSaysSoAndAsksForNothing() {
        income("25000000");

        ProfileResponse p = service.profile(SEP_23, "owner");

        assertThat(p.getLevel()).isEqualTo(2);
        assertThat(p.getRule().getReason()).isEqualTo("NO_RULE");
        assertThat(p.getBuckets()).extracting(Bucket::getBucket)
                .containsExactly("DONATION", "EMERGENCY", "INVESTMENTS");
        assertThat(p.getBuckets()).allSatisfy(b -> {
            assertThat(b.getPercent()).isEqualByComparingTo("0");
            assertThat(b.getAmount()).isEqualByComparingTo("0");
            assertThat(b.getNormalMonthAmount()).isEqualByComparingTo("0");
        });
        assertThat(p.getTotalAmount()).isEqualByComparingTo("0");
    }

    /**
     * 90M or more left after bills is above the top step: the engine gives no level and no
     * guidance there, so neither does the profile. Level 6 still points at that ceiling.
     */
    @Test
    void aboveTheTopStepThereIsNoLevelAndLevelSixPointsAtIt() {
        income("100000000");
        ProfileResponse above = service.profile(SEP_23, "owner");
        assertThat(above.isAboveCeiling()).isTrue();
        assertThat(above.getLevel()).isNull();
        assertThat(above.getLevelFrom()).isEqualByComparingTo("90000000");
        assertThat(above.getNextLevelAt()).isNull();
        assertThat(above.getRule().getReason()).isEqualTo("NO_RULE");
        assertThat(above.getTotalPercent()).isEqualByComparingTo("0");

        income("80000000");
        ProfileResponse six = service.profile(SEP_23, "owner");
        assertThat(six.isAboveCeiling()).isFalse();
        assertThat(six.getLevel()).isEqualTo(6);
        assertThat(six.getLevelFrom()).isEqualByComparingTo("75000000");
        assertThat(six.getNextLevelAt()).isEqualByComparingTo("90000000");
    }

    /**
     * Before allocation tracking starts the Plan asks for nothing, but the configuration is already
     * known: the profile shows it (Level 1 with no debt: 10 / 5 / 15 % of the 10M stable income).
     */
    @Test
    void theRuleIsShownEvenBeforeTrackingStarts() {
        income("10000000");
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 10, 1));
        MonthlyPayment rent = new MonthlyPayment();
        rent.setId(1L);
        rent.setName("Rent");
        rent.setAmount(new BigDecimal("4200000"));
        rent.setCurrency(Currency.UZS);
        rent.setActive(true);
        bills.add(rent);

        ProfileResponse p = service.profile(SEP_23, "owner");

        assertThat(overview.getTierIgnoringSubscriptions(YearMonth.of(2026, 9), Currency.UZS)
                .getAllocation().getLines()).isEmpty();                   // the Plan: paused
        assertThat(p.getRule().getReason()).isEqualTo("NO_DEBT");
        assertThat(p.getLeftAfterBills()).isEqualByComparingTo("5800000");
        assertThat(p.getBuckets())
                .extracting(b -> b.getPercent().toPlainString(), b -> b.getAmount().stripTrailingZeros().toPlainString())
                .containsExactly(tuple("10", "1000000"), tuple("5", "500000"), tuple("15", "1500000"));
    }
}
