package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.ProfileResponse;
import uz.tracker.trackerproject.dto.response.ProfileResponse.AllocatedLine;
import uz.tracker.trackerproject.dto.response.ProfileResponse.AllocatedThisMonth;
import uz.tracker.trackerproject.dto.response.ProfileResponse.BaseLine;
import uz.tracker.trackerproject.dto.response.ProfileResponse.BaseParts;
import uz.tracker.trackerproject.dto.response.ProfileResponse.Bucket;
import uz.tracker.trackerproject.dto.response.ProfileResponse.IncomeLine;
import uz.tracker.trackerproject.dto.response.ProfileResponse.IncomeThisMonth;
import uz.tracker.trackerproject.dto.response.ProfileResponse.NextBucket;
import uz.tracker.trackerproject.dto.response.ProfileResponse.NextMonth;
import uz.tracker.trackerproject.dto.response.ProfileResponse.Rule;
import uz.tracker.trackerproject.dto.response.TierAllocation;
import uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The owner's profile: the level as the 15M step it is (1..6, never "1.2"), and how their savings
 * percentages come from their stable income. It re-derives nothing: every figure is read off the
 * tier {@link OverviewService} computes for the Plan and the advisor — this month's, and next
 * month's to show what changes when a repayment plan starts or a loan ends. Beside it, the month's
 * facts: the income earned so far by category, and what has been set aside against the rule.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    /** The buckets, always all three, in this order. */
    static final List<String> BUCKETS = List.of("DONATION", "EMERGENCY", "INVESTMENTS");
    /** The allocated-this-month line for money put into savings goals (outside the buckets). */
    static final String GOALS = "GOALS";
    /** The income line for money recorded without a category. */
    static final String UNCATEGORIZED = "Uncategorized";
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OverviewService overviewService;
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public ProfileResponse profile(LocalDate date, String username) {
        if (date == null) throw new IllegalArgumentException("date is required (YYYY-MM-DD)");
        YearMonth month = YearMonth.from(date);
        OverviewTierResponse tier = overviewService.getTierForProfile(month, date);
        Set<Long> salaryTree = overviewService.salaryTree();
        IncomeThisMonth income = incomeThisMonth(month.atDay(1), date, salaryTree);
        if (tier.isMissingStableIncome()) {
            return ProfileResponse.builder()
                    .username(username).month(month.toString()).missingStableIncome(true)
                    .monthlyBills(nz(tier.getMandatorySubscriptions()))
                    .buckets(List.of())
                    .incomeThisMonth(income)
                    .allocatedThisMonth(allocatedThisMonth(month, date, income.getTotal(), null, null))
                    .build();
        }

        BigDecimal leftAfterBills = nz(tier.getLeftMoney());
        BigDecimal loanPayments = nz(tier.getDebtPayments());
        BigDecimal leftForSavings = clampZero(leftAfterBills.subtract(loanPayments));
        Integer level = tier.getLevel();
        // The income is set, so the engine leaves the level out only above its top breakpoint.
        boolean aboveCeiling = level == null;
        BigDecimal stable = nz(tier.getIncome());
        BigDecimal salaryReceived = nz(tier.getSalaryReceived());
        // A month without a bonus: the base is the salary, never less than Settings.
        BigDecimal salaryBase = stable.max(salaryReceived);
        BigDecimal savingsBase = nz(tier.getAllocationBase());

        List<Bucket> buckets = new ArrayList<>(BUCKETS.size());
        for (String name : BUCKETS) {
            AllocationLine line = askedLine(tier.getAllocation(), name);
            BigDecimal percent = line == null ? BigDecimal.ZERO : line.getMinPercent();
            buckets.add(Bucket.builder()
                    .bucket(name)
                    .percent(percent)
                    // The engine's own figure, so it is the advisor's savingsThisMonth target.
                    .amount(line == null ? BigDecimal.ZERO : nz(line.getMinAmount()))
                    .normalMonthAmount(share(salaryBase, percent))
                    .build());
        }
        Rule rule = rule(tier.getAllocation());

        return ProfileResponse.builder()
                .username(username)
                .month(month.toString())
                .missingStableIncome(false)
                .level(level)
                .aboveCeiling(aboveCeiling)
                .levelFrom(aboveCeiling ? OverviewService.tierCeiling() : OverviewService.levelIncomeLow(level))
                .nextLevelAt(aboveCeiling ? null : OverviewService.levelIncomeHigh(level))
                .stableIncome(stable)
                .monthlyBills(nz(tier.getMandatorySubscriptions()))
                .leftAfterBills(leftAfterBills)
                .loanPayments(loanPayments)
                .leftForSavings(leftForSavings)
                .bonusThisMonth(nz(tier.getBonusIncome()))
                .savingsBase(savingsBase)
                .baseParts(BaseParts.builder()
                        .salaryReceived(salaryReceived)
                        .stableIncome(stable)
                        .usesStableIncome(stable.compareTo(salaryReceived) > 0)
                        .bonus(nz(tier.getBonusIncome()))
                        .lines(baseLines(month, date, salaryTree))
                        .build())
                .rule(rule)
                .buckets(buckets)
                .totalPercent(buckets.stream().map(Bucket::getPercent).reduce(BigDecimal.ZERO, BigDecimal::add))
                .totalAmount(buckets.stream().map(Bucket::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .normalMonthTotal(buckets.stream().map(Bucket::getNormalMonthAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .nextMonth(nextMonth(month.plusMonths(1), date, rule, buckets))
                .incomeThisMonth(income)
                .allocatedThisMonth(allocatedThisMonth(month, date, income.getTotal(), savingsBase, buckets))
                .build();
    }

    /** One income line while it is being added up. */
    private static final class IncomeGroup {
        final Category category;
        final boolean inBase;
        BigDecimal amount = BigDecimal.ZERO;

        IncomeGroup(Category category, boolean inBase) {
            this.category = category;
            this.inBase = inBase;
        }

        IncomeLine line() {
            return IncomeLine.builder()
                    .categoryId(category == null ? null : category.getId())
                    .name(category == null ? UNCATEGORIZED : category.getName())
                    .nameUz(category == null ? null : category.getNameUz())
                    .amount(amount)
                    .inBase(inBase)
                    .build();
        }

        BaseLine baseLine() {
            IncomeLine l = line();
            return BaseLine.builder().categoryId(l.getCategoryId()).name(l.getName()).nameUz(l.getNameUz())
                    .amount(amount).build();
        }
    }

    /** Whether income in {@code category} is in the savings base: salary (its category tree) or bonus. */
    private static boolean inBase(Category category, Set<Long> salaryTree) {
        return OverviewService.isBonusCategory(category) || OverviewService.isSalaryCategory(category, salaryTree);
    }

    /**
     * The income behind the savings base, by category, largest first: the salary rows the engine
     * counts (salary tree, dated up to today) and the bonus rows it adds (the whole month).
     */
    private List<BaseLine> baseLines(YearMonth month, LocalDate date, Set<Long> salaryTree) {
        Map<Long, IncomeGroup> groups = new LinkedHashMap<>();
        List<Transaction> rows = transactionRepository.findByTransactionDateBetween(month.atDay(1), month.atEndOfMonth());
        for (Transaction t : rows == null ? List.<Transaction>of() : rows) {
            if (t.getType() != TransactionType.INCOME || t.getAmount() == null || !isUzs(t.getCurrency())) continue;
            Category category = t.getCategory();
            boolean bonus = OverviewService.isBonusCategory(category);
            boolean salary = t.getSubType() == TransactionSubType.REGULAR_INCOME && !t.getTransactionDate().isAfter(date)
                    && OverviewService.isSalaryCategory(category, salaryTree);
            if (!bonus && !salary) continue;
            IncomeGroup group = groups.computeIfAbsent(category == null ? null : category.getId(),
                    id -> new IncomeGroup(category, true));
            group.amount = group.amount.add(t.getAmount());
        }
        return groups.values().stream()
                .sorted(Comparator.comparing((IncomeGroup g) -> g.amount).reversed())
                .map(IncomeGroup::baseLine)
                .toList();
    }

    /**
     * This month's income, dated {@code date} or earlier, by the category each row was recorded in
     * — its own leaf category, so the salary's advance is a line of its own — largest first. Only
     * earned money: borrowed money and loans paid back are left out (and added up beside), and so
     * are transfers between the owner's own wallets, a check-in's surplus correction and non-UZS pots.
     */
    private IncomeThisMonth incomeThisMonth(LocalDate start, LocalDate date, Set<Long> salaryTree) {
        Map<Long, IncomeGroup> groups = new LinkedHashMap<>();
        BigDecimal borrowed = BigDecimal.ZERO;
        BigDecimal returned = BigDecimal.ZERO;
        List<Transaction> rows = transactionRepository.findByTransactionDateBetween(start, date);
        for (Transaction t : rows == null ? List.<Transaction>of() : rows) {
            if (t.getType() != TransactionType.INCOME || t.getAmount() == null || !isUzs(t.getCurrency())) continue;
            TransactionSubType st = t.getSubType();
            if (st == TransactionSubType.LOAN_RECEIVED) {
                borrowed = borrowed.add(t.getAmount());
                continue;
            }
            if (st == TransactionSubType.LOAN_RETURNED_TO_ME) {
                returned = returned.add(t.getAmount());
                continue;
            }
            if (st == TransactionSubType.TRANSFER_IN || t.getTransferPairId() != null) continue;
            if (st == TransactionSubType.EVERYDAY_SPENDING) continue;   // a check-in surplus: a correction
            Category category = t.getCategory();
            IncomeGroup group = groups.computeIfAbsent(category == null ? null : category.getId(),
                    id -> new IncomeGroup(category, inBase(category, salaryTree)));
            group.amount = group.amount.add(t.getAmount());
        }
        List<IncomeLine> lines = groups.values().stream().map(IncomeGroup::line)
                .sorted(Comparator.comparing(IncomeLine::getAmount).reversed()
                        .thenComparing(IncomeLine::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return IncomeThisMonth.builder()
                .total(lines.stream().map(IncomeLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .lines(lines)
                .excludedBorrowed(borrowed)
                .excludedReturned(returned)
                .build();
    }

    /**
     * What has been set aside this month: each bucket at exactly the figure the advisor calls paid
     * (the Plan's own sum, "already paid" marks included), against its rule amount — then savings
     * goals, when anything went into one by {@code date}.
     *
     * @param targets this month's profile buckets, for their rule amounts; null without a stable income
     */
    private AllocatedThisMonth allocatedThisMonth(YearMonth month, LocalDate date, BigDecimal income,
                                                  BigDecimal savingsBase, List<Bucket> targets) {
        OverviewService.BucketPaid paid = overviewService.computePaidThisMonth(month, Currency.UZS);
        BigDecimal[] amounts = paid == null
                ? new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}
                : new BigDecimal[]{nz(paid.donation()), nz(paid.emergency()), nz(paid.investments())};
        List<AllocatedLine> lines = new ArrayList<>(BUCKETS.size() + 1);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < BUCKETS.size(); i++) {
            BigDecimal target = targets == null ? null : targets.get(i).getAmount();
            lines.add(AllocatedLine.builder()
                    .bucket(BUCKETS.get(i))
                    .amount(amounts[i])
                    .percentOfIncome(percentOf(amounts[i], income))
                    .percentOfBase(percentOf(amounts[i], savingsBase))
                    .target(target)
                    .over(target == null ? null : clampZero(amounts[i].subtract(target)))
                    .build());
            total = total.add(amounts[i]);
        }
        BigDecimal goals = BigDecimal.ZERO;
        for (Transaction t : transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                TransactionSubType.INVESTMENT, month.atDay(1), date)) {
            if (t.getAmount() == null || t.getAmount().signum() <= 0 || !isUzs(t.getCurrency())) continue;
            if (overviewService.isSavingsGoalTx(t)) goals = goals.add(t.getAmount());
        }
        if (goals.signum() > 0) {
            lines.add(AllocatedLine.builder().bucket(GOALS).amount(goals).percentOfIncome(percentOf(goals, income))
                    .percentOfBase(percentOf(goals, savingsBase)).build());
            total = total.add(goals);
        }
        return AllocatedThisMonth.builder().total(total).percentOfIncome(percentOf(total, income))
                .percentOfBase(percentOf(total, savingsBase)).lines(lines).build();
    }

    /** {@code amount} as a percentage of {@code income}, one decimal; null while there is no income. */
    private static BigDecimal percentOf(BigDecimal amount, BigDecimal income) {
        if (income == null || income.signum() == 0) return null;
        return amount.multiply(HUNDRED).divide(income, 1, RoundingMode.HALF_UP);
    }

    private static boolean isUzs(Currency c) {
        return c == null || c == Currency.UZS;
    }

    /**
     * Next month's rule and percentages — or null when neither changes, so the page only says
     * "from October" when there is something to say.
     */
    private NextMonth nextMonth(YearMonth next, LocalDate date, Rule thisRule, List<Bucket> thisBuckets) {
        OverviewTierResponse tier = overviewService.getTierForProfile(next, date);
        BigDecimal leftForSavings = clampZero(nz(tier.getLeftMoney()).subtract(nz(tier.getDebtPayments())));
        // Before it begins nothing is received: the month's base without a bonus is the stable income.
        BigDecimal salaryBase = nz(tier.getIncome()).max(nz(tier.getSalaryReceived()));
        Rule rule = rule(tier.getAllocation());
        List<NextBucket> buckets = new ArrayList<>(BUCKETS.size());
        boolean same = rule.getReason().equals(thisRule.getReason());
        for (int i = 0; i < BUCKETS.size(); i++) {
            AllocationLine line = askedLine(tier.getAllocation(), BUCKETS.get(i));
            BigDecimal percent = line == null ? BigDecimal.ZERO : line.getMinPercent();
            same &= percent.compareTo(thisBuckets.get(i).getPercent()) == 0;
            buckets.add(NextBucket.builder().bucket(BUCKETS.get(i)).percent(percent)
                    .normalMonthAmount(share(salaryBase, percent)).build());
        }
        if (same) return null;
        return NextMonth.builder()
                .month(next.toString())
                .reason(rule.getReason())
                .loanPayments(nz(tier.getDebtPayments()))
                .leftForSavings(leftForSavings)
                .buckets(buckets)
                .build();
    }

    /** The allocation line for {@code bucket} when this month's rule asks for it; null when it does not. */
    private static AllocationLine askedLine(TierAllocation allocation, String bucket) {
        if (allocation == null || allocation.getLines() == null) return null;
        return allocation.getLines().stream()
                .filter(l -> bucket.equals(l.getBucket()) && l.isRecommended() && l.getMinPercent() != null)
                .findFirst().orElse(null);
    }

    /**
     * Which rule chose the percentages, from the scenario the engine picked. Level 1's are built in;
     * a Level 2–6 sub-level carries the owner's own rule (its key is the sub-level, "2.1"), or none —
     * the engine then defines no allocation, as it does above the ceiling.
     */
    private Rule rule(TierAllocation allocation) {
        String key = allocation == null ? null : allocation.getScenarioKey();
        String reason = key == null ? "NO_RULE" : switch (key) {
            case "1.1" -> "NO_DEBT";
            case "1.2.1.comfortable" -> "BANK_LOAN_COMFORTABLE";
            case "1.2.1.tight" -> "BANK_LOAN_TIGHT";
            case "1.2.2.comfortable" -> "DEBTS_COMFORTABLE";
            case "1.2.2.tight" -> "DEBTS_TIGHT";
            case "1.2.3" -> "BANK_AND_DEBTS";
            case "1.3" -> "HEAVY_DEBT";
            default -> "CUSTOM";
        };
        // Only the bank-loan-only and debts-only rules split tight / comfortable (Level 1's cutoff).
        boolean split = key != null && (key.startsWith("1.2.1.") || key.startsWith("1.2.2."));
        return Rule.builder().reason(reason).cutoff(split ? overviewService.minLeftoverUzs(1) : null).build();
    }

    /** {@code percent} % of {@code base}, the way the engine computes a bucket. */
    private static BigDecimal share(BigDecimal base, BigDecimal percent) {
        if (percent.signum() == 0) return BigDecimal.ZERO;
        return base.multiply(percent, MC).divide(HUNDRED, MC);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal clampZero(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }
}
