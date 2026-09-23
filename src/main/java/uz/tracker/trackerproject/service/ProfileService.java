package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.ProfileResponse;
import uz.tracker.trackerproject.dto.response.ProfileResponse.Bucket;
import uz.tracker.trackerproject.dto.response.ProfileResponse.NextBucket;
import uz.tracker.trackerproject.dto.response.ProfileResponse.NextMonth;
import uz.tracker.trackerproject.dto.response.ProfileResponse.Rule;
import uz.tracker.trackerproject.dto.response.TierAllocation;
import uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * The owner's profile: the level as the 15M step it is (1..6, never "1.2"), and how their savings
 * percentages come from their stable income. It re-derives nothing: every figure is read off the
 * tier {@link OverviewService} computes for the Plan and the advisor — this month's, and next
 * month's to show what changes when a repayment plan starts or a loan ends.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    /** The buckets, always all three, in this order. */
    static final List<String> BUCKETS = List.of("DONATION", "EMERGENCY", "INVESTMENTS");
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OverviewService overviewService;

    @Transactional(readOnly = true)
    public ProfileResponse profile(LocalDate date, String username) {
        if (date == null) throw new IllegalArgumentException("date is required (YYYY-MM-DD)");
        YearMonth month = YearMonth.from(date);
        OverviewTierResponse tier = overviewService.getTierForProfile(month);
        if (tier.isMissingStableIncome()) {
            return ProfileResponse.builder()
                    .username(username).month(month.toString()).missingStableIncome(true)
                    .monthlyBills(nz(tier.getMandatorySubscriptions()))
                    .buckets(List.of())
                    .build();
        }

        BigDecimal leftAfterBills = nz(tier.getLeftMoney());
        BigDecimal loanPayments = nz(tier.getDebtPayments());
        BigDecimal leftForSavings = clampZero(leftAfterBills.subtract(loanPayments));
        Integer level = tier.getLevel();
        // The income is set, so the engine leaves the level out only above its top breakpoint.
        boolean aboveCeiling = level == null;

        List<Bucket> buckets = new ArrayList<>(BUCKETS.size());
        for (String name : BUCKETS) {
            AllocationLine line = askedLine(tier.getAllocation(), name);
            BigDecimal percent = line == null ? BigDecimal.ZERO : line.getMinPercent();
            buckets.add(Bucket.builder()
                    .bucket(name)
                    .percent(percent)
                    // The engine's own figure, so it is the advisor's savingsThisMonth target.
                    .amount(line == null ? BigDecimal.ZERO : nz(line.getMinAmount()))
                    .normalMonthAmount(share(leftForSavings, percent))
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
                .stableIncome(nz(tier.getIncome()))
                .monthlyBills(nz(tier.getMandatorySubscriptions()))
                .leftAfterBills(leftAfterBills)
                .loanPayments(loanPayments)
                .leftForSavings(leftForSavings)
                .bonusThisMonth(nz(tier.getBonusIncome()))
                .savingsBase(nz(tier.getAllocationBase()))
                .rule(rule)
                .buckets(buckets)
                .totalPercent(buckets.stream().map(Bucket::getPercent).reduce(BigDecimal.ZERO, BigDecimal::add))
                .totalAmount(buckets.stream().map(Bucket::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .normalMonthTotal(buckets.stream().map(Bucket::getNormalMonthAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .nextMonth(nextMonth(month.plusMonths(1), rule, buckets))
                .build();
    }

    /**
     * Next month's rule and percentages — or null when neither changes, so the page only says
     * "from October" when there is something to say.
     */
    private NextMonth nextMonth(YearMonth next, Rule thisRule, List<Bucket> thisBuckets) {
        OverviewTierResponse tier = overviewService.getTierForProfile(next);
        BigDecimal leftForSavings = clampZero(nz(tier.getLeftMoney()).subtract(nz(tier.getDebtPayments())));
        Rule rule = rule(tier.getAllocation());
        List<NextBucket> buckets = new ArrayList<>(BUCKETS.size());
        boolean same = rule.getReason().equals(thisRule.getReason());
        for (int i = 0; i < BUCKETS.size(); i++) {
            AllocationLine line = askedLine(tier.getAllocation(), BUCKETS.get(i));
            BigDecimal percent = line == null ? BigDecimal.ZERO : line.getMinPercent();
            same &= percent.compareTo(thisBuckets.get(i).getPercent()) == 0;
            buckets.add(NextBucket.builder().bucket(BUCKETS.get(i)).percent(percent)
                    .normalMonthAmount(share(leftForSavings, percent)).build());
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
