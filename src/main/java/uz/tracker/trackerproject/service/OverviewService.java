package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse.BucketLedger;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse.MonthBreakdown;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse.MonthBucketLine;
import uz.tracker.trackerproject.dto.response.OverviewIncomeResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation;
import uz.tracker.trackerproject.dto.response.TierAllocation.ActionItem;
import uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.Donation;
import uz.tracker.trackerproject.entity.Emergency;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LevelAllocationRule;
import uz.tracker.trackerproject.entity.LevelConfig;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.EmergencyRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.LevelAllocationRuleRepository;
import uz.tracker.trackerproject.repository.LevelConfigRepository;
import uz.tracker.trackerproject.repository.MonthlyPaymentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;
import uz.tracker.trackerproject.dto.request.LevelAllocationRuleRequest;
import uz.tracker.trackerproject.dto.request.LevelConfigRequest;
import uz.tracker.trackerproject.dto.response.AllocationRulesViewResponse;
import uz.tracker.trackerproject.dto.response.AllocationRulesViewResponse.LevelView;
import uz.tracker.trackerproject.dto.response.AllocationRulesViewResponse.SubLevelView;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OverviewService {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);
    private static final BigDecimal DEBT_RATIO_THRESHOLD = new BigDecimal("0.70");
    private static final BigDecimal[] LEVEL_BREAKPOINTS_UZS = {
            new BigDecimal("15000000"),  // < this → level 1
            new BigDecimal("30000000"),  // < this → level 2
            new BigDecimal("45000000"),  // < this → level 3
            new BigDecimal("60000000"),  // < this → level 4
            new BigDecimal("75000000"),  // < this → level 5
            new BigDecimal("90000000"),  // < this → level 6
    };

    /** Threshold for "tight vs comfortable" within Level 1.2 / 1.3 (5M UZS). */
    private static final BigDecimal FIVE_MILLION_UZS = new BigDecimal("5000000");
    /** Recommended personal-loan repayment portion ("at least 34% per month"). */
    private static final BigDecimal PERSONAL_LOAN_PAYDOWN_RATE = new BigDecimal("0.34");
    /**
     * Fraction of a bank installment's average monthly amount that must be paid for the
     * action to count as "met" and unlock allocation recording (90% of the average).
     */
    private static final BigDecimal BANK_UNLOCK_RATE = new BigDecimal("0.90");

    private final TransactionRepository transactionRepository;
    private final MonthlyPaymentRepository monthlyPaymentRepository;
    private final BankLoanRepository bankLoanRepository;
    private final LoanTakenRepository loanTakenRepository;
    private final DebtRepository debtRepository;
    private final DonationRepository donationRepository;
    private final EmergencyRepository emergencyRepository;
    private final InvestmentRepository investmentRepository;
    private final LevelAllocationRuleRepository ruleRepository;
    private final LevelConfigRepository levelConfigRepository;
    private final SettingsService settingsService;
    private final FxConverter fx;

    @Transactional(readOnly = true)
    public OverviewIncomeResponse getIncome(YearMonth month, Currency displayCurrency) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        // Sum INCOME transactions per currency in this month, FX-convert each to the
        // display currency, then total. Iterating the enum keeps this future-proof if
        // we add more currencies later.
        BigDecimal actual = BigDecimal.ZERO;
        for (Currency c : Currency.values()) {
            BigDecimal sum = transactionRepository.sumByTypeCurrencyDateRange(
                    TransactionType.INCOME, c, start, end);
            if (sum == null || sum.signum() == 0) continue;
            actual = actual.add(fx.convert(sum, c, displayCurrency));
        }

        Settings s = settingsService.getOrCreate();
        BigDecimal stable = null;
        if (s.getMonthlyStableIncome() != null && s.getMonthlyStableIncomeCurrency() != null) {
            stable = fx.convert(s.getMonthlyStableIncome(), s.getMonthlyStableIncomeCurrency(), displayCurrency);
        }

        boolean usingDefaults =
                (s.getUsdToUzs() == null || s.getUsdToUzs().signum() <= 0)
                || (s.getEurToUzs() == null || s.getEurToUzs().signum() <= 0);

        return OverviewIncomeResponse.builder()
                .month(month.toString())
                .currency(displayCurrency)
                .actualIncome(actual)
                .stableIncome(stable)
                .fxRatesUsingDefaults(usingDefaults)
                .build();
    }

    // ── Tier ──────────────────────────────────────────────────────────────────

    /**
     * Compute the user's financial tier for a given month. Level/sub-level reflect
     * the CURRENT snapshot (debts, subscriptions, income) — we don't store historical
     * snapshots — but the per-bucket "paid this month" math IS scoped to the requested
     * month. So switching months shows the current tier with that month's payment
     * progress.
     */
    @Transactional(readOnly = true)
    public OverviewTierResponse getTier(YearMonth month, Currency displayCurrency) {
        Settings s = settingsService.getOrCreate();
        boolean missingIncome = s.getMonthlyStableIncome() == null
                || s.getMonthlyStableIncomeCurrency() == null
                || s.getMonthlyStableIncome().signum() <= 0;

        BigDecimal incomeUzs = missingIncome ? BigDecimal.ZERO
                : fx.toUzs(s.getMonthlyStableIncome(), s.getMonthlyStableIncomeCurrency());

        BigDecimal mandatoryUzs = sumActiveSubscriptionsUzs();
        BigDecimal leftMoneyUzs = incomeUzs.subtract(mandatoryUzs);

        LocalDate today = LocalDate.now();
        BigDecimal bankUzs = sumBankLoanMonthlyPaymentsUzs(today);
        // Personal loans / debts only count from their payment-start month onward, so
        // borrowing now (with a future start) doesn't move the tier for the viewed month.
        BigDecimal loansTakenUzs = sumLoansTakenMonthlyUzs(today, month);
        BigDecimal debtsUzs = sumDebtsMonthlyUzs(today, month);
        BigDecimal debtTotalUzs = bankUzs.add(loansTakenUzs).add(debtsUzs);

        BigDecimal debtRatio = incomeUzs.signum() > 0
                ? debtTotalUzs.divide(incomeUzs, MC)
                : null;

        Integer level = missingIncome ? null : computeLevel(leftMoneyUzs);
        String subLevel = computeSubLevel(level, debtTotalUzs, debtRatio);
        String levelLabel = computeLevelLabel(level, subLevel, missingIncome);

        // Personal-loan totals (remaining) used by sub-level rule branches for
        // the recommended 34% pay-down. Note these are TOTAL REMAINING — not the
        // derived monthly contribution used for the sub-level ratio. Also gated by
        // payment-start so a not-yet-started loan doesn't appear in this month's guidance.
        BigDecimal personalLoansRemainingUzs = sumPersonalLoansRemainingUzs(month);

        // Bonus income this month tops up the allocation target: the same tier % is
        // applied to the bonus and added to each bucket's recommended amount. The level /
        // sub-level themselves stay anchored to stable income only.
        BigDecimal bonusUzs = sumBonusIncomeUzs(month);
        BigDecimal incomeBaseUzs = incomeUzs.add(bonusUzs);

        // Paid-this-month per bucket (already FX-converted to the display currency).
        BucketPaid paid = computePaidThisMonth(month, displayCurrency);

        // Paid-this-month for the two debt-pay actions, so the guidance card can show
        // "you've paid X of Y this month" without the tier itself shifting.
        MonthPaid monthPaid = computeMonthPaid(month, displayCurrency);

        TierAllocation allocation = missingIncome
                ? notDefinedAllocation("Set monthly income to see allocation guidance.")
                : computeAllocation(level, subLevel, incomeUzs, incomeBaseUzs, mandatoryUzs,
                        bankUzs, personalLoansRemainingUzs, displayCurrency, paid, monthPaid);

        boolean usingDefaults =
                (s.getUsdToUzs() == null || s.getUsdToUzs().signum() <= 0)
                || (s.getEurToUzs() == null || s.getEurToUzs().signum() <= 0);

        return OverviewTierResponse.builder()
                .currency(displayCurrency)
                .income(fx.fromUzs(incomeUzs, displayCurrency))
                .mandatorySubscriptions(fx.fromUzs(mandatoryUzs, displayCurrency))
                .leftMoney(fx.fromUzs(leftMoneyUzs, displayCurrency))
                .debtPayments(fx.fromUzs(debtTotalUzs, displayCurrency))
                .debtBreakdown(OverviewTierResponse.DebtBreakdown.builder()
                        .bankLoans(fx.fromUzs(bankUzs, displayCurrency))
                        .loansTaken(fx.fromUzs(loansTakenUzs, displayCurrency))
                        .debts(fx.fromUzs(debtsUzs, displayCurrency))
                        .build())
                .debtRatio(debtRatio)
                .level(level)
                .subLevel(subLevel)
                .levelLabel(levelLabel)
                .fxRatesUsingDefaults(usingDefaults)
                .missingStableIncome(missingIncome)
                .allocation(allocation)
                .build();
    }

    // ── Allocation ledger (cross-month backlog) ────────────────────────────────

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String[] LEDGER_BUCKETS = {"DONATION", "EMERGENCY", "INVESTMENTS", "STOCKS"};
    private static final String[] LEDGER_LABELS = {"Donation", "Emergency", "Investments", "Stocks"};

    /**
     * Running allocation ledger from the configured start month to {@code selected}. For each
     * month we recompute the tier scenario (so the % can vary as debts start/clear), apply it
     * to stable income + that month's bonus to get the recommended amount, and net it against
     * what was actually paid. The balance is cumulative — overpaying a later month clears an
     * earlier shortfall. Level/sub-level stay anchored to stable income.
     */
    @Transactional(readOnly = true)
    public AllocationLedgerResponse getAllocationLedger(YearMonth selected, Currency display) {
        Settings s = settingsService.getOrCreate();
        boolean missingIncome = s.getMonthlyStableIncome() == null
                || s.getMonthlyStableIncomeCurrency() == null
                || s.getMonthlyStableIncome().signum() <= 0;

        YearMonth start = s.getAllocationTrackingStartMonth() != null
                ? YearMonth.from(s.getAllocationTrackingStartMonth())
                : YearMonth.now();
        if (start.isAfter(selected)) start = selected; // never iterate backwards

        if (missingIncome) {
            return AllocationLedgerResponse.builder()
                    .currency(display)
                    .startMonth(start.toString())
                    .selectedMonth(selected.toString())
                    .missingStableIncome(true)
                    .buckets(List.of())
                    .months(List.of())
                    .build();
        }

        BigDecimal stableUzs = fx.toUzs(s.getMonthlyStableIncome(), s.getMonthlyStableIncomeCurrency());
        BigDecimal mandatoryUzs = sumActiveSubscriptionsUzs();
        Integer level = computeLevel(stableUzs.subtract(mandatoryUzs));
        LocalDate today = LocalDate.now();

        BigDecimal[] totalRec = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] totalPaid = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] prevBalance = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] recSelected = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] paidSelected = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        String[] pctSelected = new String[4];
        BigDecimal bonusSelectedUzs = BigDecimal.ZERO;
        String subLevelSelected = null;

        List<MonthBreakdown> months = new ArrayList<>();
        YearMonth earliestDue = null, latestDue = null;

        for (YearMonth m = start; !m.isAfter(selected); m = m.plusMonths(1)) {
            BigDecimal bankUzs = sumBankLoanMonthlyPaymentsUzs(today);
            BigDecimal loansTakenUzs = sumLoansTakenMonthlyUzs(today, m);
            BigDecimal debtsUzs = sumDebtsMonthlyUzs(today, m);
            BigDecimal debtTotalUzs = bankUzs.add(loansTakenUzs).add(debtsUzs);
            BigDecimal debtRatio = stableUzs.signum() > 0 ? debtTotalUzs.divide(stableUzs, MC) : null;
            String subLevel = computeSubLevel(level, debtTotalUzs, debtRatio);
            BigDecimal personalRemUzs = sumPersonalLoansRemainingUzs(m);
            String[] pct = bucketPercents(level, subLevel, stableUzs, mandatoryUzs, bankUzs, personalRemUzs);

            BigDecimal bonusUzs = sumBonusIncomeUzs(m);
            BigDecimal incomeBaseUzs = stableUzs.add(bonusUzs);

            BucketPaid paidUzs = computePaidThisMonth(m, Currency.UZS);
            BigDecimal[] paidArr = {paidUzs.donation(), paidUzs.emergency(), paidUzs.investments(), paidUzs.stocks()};

            boolean isSelected = m.equals(selected);
            boolean monthHasActivity = false;
            BigDecimal monthNetUzs = BigDecimal.ZERO;
            List<MonthBucketLine> lines = new ArrayList<>(4);

            for (int b = 0; b < 4; b++) {
                BigDecimal recUzs = pct[b] == null ? BigDecimal.ZERO
                        : incomeBaseUzs.multiply(new BigDecimal(pct[b]), MC).divide(HUNDRED, MC);
                BigDecimal paidB = paidArr[b];
                BigDecimal net = recUzs.subtract(paidB);

                totalRec[b] = totalRec[b].add(recUzs);
                totalPaid[b] = totalPaid[b].add(paidB);
                if (!isSelected) prevBalance[b] = prevBalance[b].add(net);
                else {
                    recSelected[b] = recUzs;
                    paidSelected[b] = paidB;
                    pctSelected[b] = pct[b];
                }
                monthNetUzs = monthNetUzs.add(net);

                if (recUzs.signum() > 0 || paidB.signum() > 0) {
                    monthHasActivity = true;
                    lines.add(MonthBucketLine.builder()
                            .bucket(LEDGER_BUCKETS[b])
                            .percent(pct[b] == null ? null : new BigDecimal(pct[b]))
                            .recommended(fx.fromUzs(recUzs, display))
                            .paid(fx.fromUzs(paidB, display))
                            .net(fx.fromUzs(net, display))
                            .build());
                }
            }

            if (isSelected) {
                bonusSelectedUzs = bonusUzs;
                subLevelSelected = subLevel;
            } else if (monthNetUzs.signum() > 0) {
                if (earliestDue == null) earliestDue = m;
                latestDue = m;
            }

            if (monthHasActivity) {
                months.add(MonthBreakdown.builder()
                        .month(m.toString())
                        .level(level)
                        .subLevel(subLevel)
                        .stableIncome(fx.fromUzs(stableUzs, display))
                        .bonus(fx.fromUzs(bonusUzs, display))
                        .selected(isSelected)
                        .lines(lines)
                        .build());
            }
        }

        BigDecimal incomeBaseSelUzs = stableUzs.add(bonusSelectedUzs);
        List<BucketLedger> buckets = new ArrayList<>(4);
        BigDecimal totalDueNowUzs = BigDecimal.ZERO;
        BigDecimal carriedPrevUzs = BigDecimal.ZERO;
        BigDecimal dueThisMonthUzs = BigDecimal.ZERO;

        for (int b = 0; b < 4; b++) {
            BigDecimal balance = totalRec[b].subtract(totalPaid[b]);          // net through selected
            BigDecimal outstanding = balance.signum() > 0 ? balance : BigDecimal.ZERO;
            BigDecimal carried = prevBalance[b];                              // can be negative (ahead)
            BigDecimal effPct = (paidSelected[b].signum() > 0 && incomeBaseSelUzs.signum() > 0)
                    ? paidSelected[b].multiply(HUNDRED, MC).divide(incomeBaseSelUzs, MC)
                    : null;
            boolean over = paidSelected[b].compareTo(recSelected[b]) > 0;

            buckets.add(BucketLedger.builder()
                    .bucket(LEDGER_BUCKETS[b])
                    .label(LEDGER_LABELS[b])
                    .percent(pctSelected[b] == null ? null : new BigDecimal(pctSelected[b]))
                    .recommended(fx.fromUzs(recSelected[b], display))
                    .paid(fx.fromUzs(paidSelected[b], display))
                    .carried(fx.fromUzs(carried, display))
                    .outstanding(fx.fromUzs(outstanding, display))
                    .effectivePercent(effPct)
                    .overAllocated(over)
                    .build());

            totalDueNowUzs = totalDueNowUzs.add(outstanding);
            if (carried.signum() > 0) carriedPrevUzs = carriedPrevUzs.add(carried);
            dueThisMonthUzs = dueThisMonthUzs.add(recSelected[b]);
        }

        return AllocationLedgerResponse.builder()
                .currency(display)
                .startMonth(start.toString())
                .selectedMonth(selected.toString())
                .missingStableIncome(false)
                .stableIncome(fx.fromUzs(stableUzs, display))
                .bonusThisMonth(fx.fromUzs(bonusSelectedUzs, display))
                .level(level)
                .subLevel(subLevelSelected)
                .dueThisMonth(fx.fromUzs(dueThisMonthUzs, display))
                .carriedFromPrevious(fx.fromUzs(carriedPrevUzs, display))
                .totalDueNow(fx.fromUzs(totalDueNowUzs, display))
                .carriedStartMonth(earliestDue == null ? null : earliestDue.toString())
                .carriedEndMonth(latestDue == null ? null : latestDue.toString())
                .buckets(buckets)
                .months(months)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal sumActiveSubscriptionsUzs() {
        BigDecimal total = BigDecimal.ZERO;
        for (MonthlyPayment m : monthlyPaymentRepository.findAll()) {
            if (!Boolean.TRUE.equals(m.getActive())) continue;
            if (m.getAmount() == null || m.getCurrency() == null) continue;
            total = total.add(fx.toUzs(m.getAmount(), m.getCurrency()));
        }
        return total;
    }

    /** Bonus-tagged income received in {@code month}, normalised to UZS across currencies. */
    private BigDecimal sumBonusIncomeUzs(YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        BigDecimal total = BigDecimal.ZERO;
        for (Currency c : Currency.values()) {
            BigDecimal sum = transactionRepository.sumBonusIncomeByCurrencyDateRange(c, start, end);
            if (sum != null && sum.signum() != 0) {
                total = total.add(fx.toUzs(sum, c));
            }
        }
        return total;
    }

    private BigDecimal sumBankLoanMonthlyPaymentsUzs(LocalDate today) {
        BigDecimal total = BigDecimal.ZERO;
        for (BankLoan b : bankLoanRepository.findAll()) {
            if (b.getMonthlyPayment() == null || b.getMonthlyPayment().signum() <= 0) continue;
            // If the loan has ended, it no longer contributes.
            if (b.getEndDate() != null && b.getEndDate().isBefore(today)) continue;
            total = total.add(fx.toUzs(b.getMonthlyPayment(), b.getCurrency()));
        }
        return total;
    }

    private BigDecimal sumLoansTakenMonthlyUzs(LocalDate today, YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (LoanTaken l : loanTakenRepository.findAll()) {
            BigDecimal remaining = nullToZero(l.getTotalAmount()).subtract(nullToZero(l.getPaidAmount()));
            if (remaining.signum() <= 0) continue;
            if (!hasStartedBy(l.getPaymentStartDate(), month)) continue;
            // Frozen monthly contribution (set at creation). Falls back to a fresh
            // derivation for legacy rows that pre-date this column.
            BigDecimal contribution = l.getMonthlyPayment();
            if (contribution == null || contribution.signum() <= 0) {
                contribution = remaining.divide(
                        BigDecimal.valueOf(monthsUntilDue(today, l.getDueDate())), MC);
            }
            total = total.add(fx.toUzs(contribution, l.getCurrency()));
        }
        return total;
    }

    private BigDecimal sumDebtsMonthlyUzs(LocalDate today, YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (Debt d : debtRepository.findAll()) {
            BigDecimal remaining = nullToZero(d.getTotalAmount()).subtract(nullToZero(d.getPaidAmount()));
            if (remaining.signum() <= 0) continue;
            if (!hasStartedBy(d.getPaymentStartDate(), month)) continue;
            BigDecimal contribution = d.getMonthlyPayment();
            if (contribution == null || contribution.signum() <= 0) {
                contribution = remaining.divide(
                        BigDecimal.valueOf(monthsUntilDue(today, d.getDueDate())), MC);
            }
            total = total.add(fx.toUzs(contribution, d.getCurrency()));
        }
        return total;
    }

    /**
     * Months between today and dueDate, floor 1. Null or past due date → 1 (i.e. the
     * full remaining lands on this month). Keeps the formula well-defined and never
     * divides by zero.
     */
    private long monthsUntilDue(LocalDate today, LocalDate dueDate) {
        if (dueDate == null) return 1L;
        long months = ChronoUnit.MONTHS.between(today, dueDate);
        return Math.max(1L, months);
    }

    /**
     * Whether a loan/debt's monthly contribution counts toward the tier for the viewed
     * month. True when its payment-start month is on or before {@code month}. Legacy rows
     * (null start) always count, preserving prior behaviour.
     */
    private boolean hasStartedBy(LocalDate paymentStartDate, YearMonth month) {
        if (paymentStartDate == null) return true;
        return !YearMonth.from(paymentStartDate).isAfter(month);
    }

    private Integer computeLevel(BigDecimal leftMoneyUzs) {
        for (int i = 0; i < LEVEL_BREAKPOINTS_UZS.length; i++) {
            if (leftMoneyUzs.compareTo(LEVEL_BREAKPOINTS_UZS[i]) < 0) return i + 1;
        }
        return null; // above tier ceiling
    }

    /**
     * Debt-based sub-level for ANY level: {level}.1 no debt, .2 manageable (ratio &lt; 70%),
     * .3 heavy (≥ 70%). Level 1 keeps its hard-coded scenario logic downstream; Levels 2–6
     * use these sub-levels to look up the user-configured allocation rules.
     */
    private String computeSubLevel(Integer level, BigDecimal debtTotalUzs, BigDecimal debtRatio) {
        if (level == null) return null;
        String suffix;
        if (debtTotalUzs.signum() == 0) suffix = "1";
        else if (debtRatio != null && debtRatio.compareTo(DEBT_RATIO_THRESHOLD) < 0) suffix = "2";
        else suffix = "3";
        return level + "." + suffix;
    }

    /** Human label for a debt sub-level suffix (".1/.2/.3"). */
    private String subLevelDebtLabel(String subLevel) {
        if (subLevel == null) return "";
        if (subLevel.endsWith(".1")) return "no debt";
        if (subLevel.endsWith(".2")) return "manageable debt (< 70% of income)";
        if (subLevel.endsWith(".3")) return "heavy debt (≥ 70% of income)";
        return "";
    }

    private String computeLevelLabel(Integer level, String subLevel, boolean missingIncome) {
        if (missingIncome) return "Set monthly income to compute tier";
        if (level == null) return "Above tier 6";
        if (subLevel != null) return "Level " + subLevel;
        return "Level " + level;
    }

    private static BigDecimal nullToZero(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    /**
     * Total REMAINING amount across LoanTaken + Debt records (personal loans only,
     * not bank installments). Used to compute the recommended 34% pay-down for
     * sub-levels 1.2.2 / 1.2.3 / 1.3 — distinct from the derived monthly contribution
     * which drives the sub-level ratio.
     */
    private BigDecimal sumPersonalLoansRemainingUzs(YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (LoanTaken l : loanTakenRepository.findAll()) {
            BigDecimal remaining = nullToZero(l.getTotalAmount()).subtract(nullToZero(l.getPaidAmount()));
            if (remaining.signum() <= 0) continue;
            if (!hasStartedBy(l.getPaymentStartDate(), month)) continue;
            total = total.add(fx.toUzs(remaining, l.getCurrency()));
        }
        for (Debt d : debtRepository.findAll()) {
            BigDecimal remaining = nullToZero(d.getTotalAmount()).subtract(nullToZero(d.getPaidAmount()));
            if (remaining.signum() <= 0) continue;
            if (!hasStartedBy(d.getPaymentStartDate(), month)) continue;
            total = total.add(fx.toUzs(remaining, d.getCurrency()));
        }
        return total;
    }

    // ── Per-bucket paid-this-month + history ──────────────────────────────────

    /**
     * Holds paid-this-month totals in the display currency for each bucket. Computed
     * once per tier request and threaded through into the allocation lines.
     */
    record BucketPaid(BigDecimal donation, BigDecimal emergency, BigDecimal investments, BigDecimal stocks) {}

    /**
     * Paid-this-month sums for the two debt-pay actions, in display currency.
     * Drives the "Paid X of Y" progress strip under the action items.
     */
    record MonthPaid(BigDecimal bankInstallments, BigDecimal personalLoanRepayments) {}

    private MonthPaid computeMonthPaid(YearMonth month, Currency displayCurrency) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        BigDecimal bank = BigDecimal.ZERO;
        BigDecimal personal = BigDecimal.ZERO;
        for (Currency c : Currency.values()) {
            BigDecimal bankSum = transactionRepository.sumBySubTypeCurrencyDateRange(
                    uz.tracker.trackerproject.enums.TransactionSubType.BANK_LOAN_PAYMENT, c, start, end);
            if (bankSum != null && bankSum.signum() > 0) {
                bank = bank.add(fx.convert(bankSum, c, displayCurrency));
            }
            BigDecimal repaySum = transactionRepository.sumBySubTypeCurrencyDateRange(
                    uz.tracker.trackerproject.enums.TransactionSubType.LOAN_REPAYMENT, c, start, end);
            if (repaySum != null && repaySum.signum() > 0) {
                personal = personal.add(fx.convert(repaySum, c, displayCurrency));
            }
        }
        return new MonthPaid(bank, personal);
    }

    private BucketPaid computePaidThisMonth(YearMonth month, Currency displayCurrency) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        BigDecimal donation = BigDecimal.ZERO;
        for (Donation d : donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(start, end)) {
            donation = donation.add(fx.convert(d.getAmount(), d.getCurrency(), displayCurrency));
        }

        BigDecimal emergency = BigDecimal.ZERO;
        for (Emergency e : emergencyRepository.findByDateBetweenOrderByDateDesc(start, end)) {
            emergency = emergency.add(fx.convert(e.getAmount(), e.getCurrency(), displayCurrency));
        }

        BigDecimal investments = BigDecimal.ZERO;
        BigDecimal stocks = BigDecimal.ZERO;
        for (Investment i : investmentRepository.findByPurchaseDateBetweenOrderByPurchaseDateDesc(start, end)) {
            BigDecimal amt = fx.convert(i.getInvestedAmount(), i.getCurrency(), displayCurrency);
            if (i.getType() == InvestmentType.STOCKS) stocks = stocks.add(amt);
            else investments = investments.add(amt);
        }

        return new BucketPaid(donation, emergency, investments, stocks);
    }

    /**
     * Per-bucket payment history for a month. Returns transactions in the entity's
     * "natural" date field (donationDate / date / purchaseDate), normalised into a
     * common BucketPayment row shape so the frontend can render uniformly.
     */
    @Transactional(readOnly = true)
    public List<uz.tracker.trackerproject.dto.response.BucketPayment> getBucketPayments(
            String bucket, YearMonth month, Currency displayCurrency) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<uz.tracker.trackerproject.dto.response.BucketPayment> rows = new ArrayList<>();

        switch (bucket.toUpperCase()) {
            case "DONATION" -> donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(start, end)
                    .forEach(d -> rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                            .id(d.getId())
                            .bucket("DONATION")
                            .date(d.getDonationDate())
                            .amount(fx.convert(d.getAmount(), d.getCurrency(), displayCurrency))
                            .nativeAmount(d.getAmount())
                            .nativeCurrency(d.getCurrency())
                            .label(Boolean.TRUE.equals(d.getAnonymous()) ? "Anonymous" : d.getRecipientName())
                            .description(d.getDescription())
                            .build()));
            case "EMERGENCY" -> emergencyRepository.findByDateBetweenOrderByDateDesc(start, end)
                    .forEach(e -> rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                            .id(e.getId())
                            .bucket("EMERGENCY")
                            .date(e.getDate())
                            .amount(fx.convert(e.getAmount(), e.getCurrency(), displayCurrency))
                            .nativeAmount(e.getAmount())
                            .nativeCurrency(e.getCurrency())
                            .label("Emergency fund")
                            .description(e.getDescription())
                            .build()));
            case "INVESTMENTS" -> investmentRepository.findByPurchaseDateBetweenOrderByPurchaseDateDesc(start, end)
                    .stream().filter(i -> i.getType() != InvestmentType.STOCKS)
                    .forEach(i -> rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                            .id(i.getId())
                            .bucket("INVESTMENTS")
                            .date(i.getPurchaseDate())
                            .amount(fx.convert(i.getInvestedAmount(), i.getCurrency(), displayCurrency))
                            .nativeAmount(i.getInvestedAmount())
                            .nativeCurrency(i.getCurrency())
                            .label(i.getName())
                            .description(i.getDescription())
                            .build()));
            case "STOCKS" -> investmentRepository.findByPurchaseDateBetweenOrderByPurchaseDateDesc(start, end)
                    .stream().filter(i -> i.getType() == InvestmentType.STOCKS)
                    .forEach(i -> rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                            .id(i.getId())
                            .bucket("STOCKS")
                            .date(i.getPurchaseDate())
                            .amount(fx.convert(i.getInvestedAmount(), i.getCurrency(), displayCurrency))
                            .nativeAmount(i.getInvestedAmount())
                            .nativeCurrency(i.getCurrency())
                            .label(i.getName())
                            .description(i.getDescription())
                            .build()));
            default -> throw new IllegalArgumentException("Unknown bucket: " + bucket);
        }
        return rows;
    }

    // ── Allocation guidance ───────────────────────────────────────────────────

    /**
     * Compute the allocation recommendation for a tier. Level 1 has hard-coded rules
     * per the owner's spec; Levels 2-6 return a "not yet defined" stub until the user
     * configures their own percentages.
     */
    private TierAllocation computeAllocation(
            Integer level, String subLevel,
            BigDecimal incomeUzs, BigDecimal incomeBaseUzs, BigDecimal mandatoryUzs,
            BigDecimal bankMonthlyUzs, BigDecimal personalLoansRemainingUzs,
            Currency displayCurrency, BucketPaid paid, MonthPaid monthPaid) {

        if (level == null) {
            return notDefinedAllocation("You're above the current tier ceiling — guidance not defined yet.");
        }
        if (level != 1) {
            return computeConfiguredAllocation(level, subLevel, incomeBaseUzs, displayCurrency,
                    bankMonthlyUzs, personalLoansRemainingUzs, paid, monthPaid);
        }

        // The scenario (and thus the percentages) is decided from STABLE income only —
        // bonus never changes the level/sub-level. The bucket AMOUNTS, however, scale with
        // incomeBaseUzs = stable + this month's bonus, so a bonus month raises the targets.
        String key = scenarioKeyFor(level, subLevel, incomeUzs, mandatoryUzs,
                bankMonthlyUzs, personalLoansRemainingUzs);
        if (key == null) {
            return notDefinedAllocation("No allocation rule for sub-level " + subLevel + ".");
        }
        String[] p = percentsForKey(key);
        List<AllocationLine> lines = percentLines(incomeBaseUzs, displayCurrency, paid,
                p[0], p[1], p[2], p[3]);

        // Action items + label per scenario. Targets are in display currency.
        BigDecimal bankTarget = fx.fromUzs(bankMonthlyUzs, displayCurrency);
        BigDecimal pay34Uzs = personalLoansRemainingUzs.multiply(PERSONAL_LOAN_PAYDOWN_RATE, MC);
        BigDecimal personalTarget = fx.fromUzs(pay34Uzs, displayCurrency);

        List<ActionItem> actions = switch (key) {
            case "1.1" -> List.of();
            case "1.2.1.tight" -> List.of(
                    payBank(monthPaid, bankTarget),
                    info("After bank installments, less than 5M UZS remains. Slim allocations until things ease up."));
            case "1.2.1.comfortable" -> List.of(
                    payBank(monthPaid, bankTarget),
                    info("5M+ UZS remains after bank installments. Use the no-debt allocation."));
            case "1.2.2.tight" -> List.of(
                    payPersonal(personalTarget, displayCurrency, monthPaid, personalTarget),
                    info("After that, less than 5M UZS remains — slim allocations apply."));
            case "1.2.2.comfortable" -> List.of(
                    payPersonal(personalTarget, displayCurrency, monthPaid, personalTarget),
                    info("5M+ remains after that — use the no-debt allocation."));
            case "1.2.3.tight" -> List.of(
                    payBank(monthPaid, bankTarget),
                    payPersonal(personalTarget, displayCurrency, monthPaid, personalTarget),
                    info("Emergency fund skipped at this sub-tier — focus on debt."));
            case "1.2.3.comfortable" -> List.of(
                    payBank(monthPaid, bankTarget),
                    payPersonal(personalTarget, displayCurrency, monthPaid, personalTarget),
                    info("5M+ remains after that — use the no-debt allocation."));
            case "1.2.unknown" -> List.of(
                    info("Tier classified as 1.2 but no debt rows found — review your records."));
            case "1.3" -> List.of(
                    ActionItem.builder()
                            .text("Pay your bank installments — you may use a smaller amount than your saved default if needed.")
                            .action("PAY_BANK")
                            .paid(monthPaid.bankInstallments())
                            .target(bankTarget)
                            .unlockThreshold(bankUnlockThreshold(bankTarget))
                            .build(),
                    payPersonal(personalTarget, displayCurrency, monthPaid, personalTarget),
                    info("You may withdraw from the emergency fund if the situation gets really bad."));
            default -> List.of();
        };

        return TierAllocation.builder()
                .scenarioKey(key)
                .scenarioLabel(scenarioLabel(key))
                .lines(lines)
                .actions(actions)
                .allocationLocked(isAllocationLocked(actions))
                .build();
    }

    /**
     * Resolve the Level-1 allocation scenario key from the tier. Returns null when there's
     * no defined rule. The tight/comfortable split is decided on STABLE income (incomeUzs),
     * so a bonus month doesn't flip the scenario. Single source of truth shared by the tier
     * cards and the allocation ledger.
     */
    private String scenarioKeyFor(Integer level, String subLevel,
            BigDecimal incomeUzs, BigDecimal mandatoryUzs,
            BigDecimal bankMonthlyUzs, BigDecimal personalLoansRemainingUzs) {
        if (level == null || level != 1) return null;
        if ("1.1".equals(subLevel)) return "1.1";
        if ("1.3".equals(subLevel)) return "1.3";
        if (!"1.2".equals(subLevel)) return null;

        BigDecimal pay34 = personalLoansRemainingUzs.multiply(PERSONAL_LOAN_PAYDOWN_RATE, MC);
        boolean hasBank = bankMonthlyUzs.signum() > 0;
        boolean hasPersonal = personalLoansRemainingUzs.signum() > 0;
        BigDecimal afterSubs = incomeUzs.subtract(mandatoryUzs);
        // Tight/comfortable cutoff is the user-configurable minimum leftover for Level 1
        // (defaults to 5M when unset).
        BigDecimal cutoff = minLeftoverUzs(1);
        if (hasBank && !hasPersonal) {
            return afterSubs.subtract(bankMonthlyUzs).compareTo(cutoff) < 0
                    ? "1.2.1.tight" : "1.2.1.comfortable";
        }
        if (!hasBank && hasPersonal) {
            return afterSubs.subtract(pay34).compareTo(cutoff) < 0
                    ? "1.2.2.tight" : "1.2.2.comfortable";
        }
        if (hasBank && hasPersonal) {
            return afterSubs.subtract(bankMonthlyUzs).subtract(pay34).compareTo(cutoff) < 0
                    ? "1.2.3.tight" : "1.2.3.comfortable";
        }
        return "1.2.unknown";
    }

    /** Per-scenario bucket percentages [donation, emergency, investments, stocks]; null = not recommended. */
    private String[] percentsForKey(String key) {
        if (key == null) return new String[]{null, null, null, null};
        return switch (key) {
            case "1.1", "1.2.1.comfortable", "1.2.2.comfortable", "1.2.3.comfortable", "1.2.unknown"
                    -> new String[]{"10", "5", "15", "1"};
            case "1.2.1.tight", "1.2.2.tight" -> new String[]{"5", "2", "8", null};
            case "1.2.3.tight" -> new String[]{"5", null, "5", null};
            case "1.3" -> new String[]{"2", null, null, null};
            default -> new String[]{null, null, null, null};
        };
    }

    private String scenarioLabel(String key) {
        if (key == null) return "Guidance not yet defined for this tier";
        return switch (key) {
            case "1.1" -> "Level 1.1 — no debts";
            case "1.2.1.tight" -> "Level 1.2 — bank loan only, tight (< 5M UZS remaining)";
            case "1.2.1.comfortable" -> "Level 1.2 — bank loan only, comfortable (5M+ UZS remaining)";
            case "1.2.2.tight" -> "Level 1.2 — personal loans only, tight (< 5M UZS remaining)";
            case "1.2.2.comfortable" -> "Level 1.2 — personal loans only, comfortable (5M+ UZS remaining)";
            case "1.2.3.tight" -> "Level 1.2 — bank + personal loans, tight (< 5M UZS remaining)";
            case "1.2.3.comfortable" -> "Level 1.2 — bank + personal loans, comfortable (5M+ UZS remaining)";
            case "1.2.unknown" -> "Level 1.2 — debt composition unclear";
            case "1.3" -> "Level 1.3 — heavy debt (≥ 70% of income)";
            default -> "Guidance not yet defined for this tier";
        };
    }

    // ── Levels 2–6: user-configured allocation rules ────────────────────────────

    /**
     * Build the allocation for a Level 2–6 sub-level from the user's configured rule.
     * Unconfigured → a stub prompting them to set it (the frontend shows the editor button).
     * Debt-pay actions are still surfaced when the sub-level carries debt.
     */
    private TierAllocation computeConfiguredAllocation(
            Integer level, String subLevel, BigDecimal incomeBaseUzs, Currency displayCurrency,
            BigDecimal bankMonthlyUzs, BigDecimal personalLoansRemainingUzs,
            BucketPaid paid, MonthPaid monthPaid) {

        if (level < 2 || level > 6 || subLevel == null) {
            return notDefinedAllocation(
                    "Allocation rules for Level " + level + " will be configured by you once you reach this tier.");
        }
        LevelAllocationRule rule = ruleRepository.findBySubLevel(subLevel).orElse(null);
        if (rule == null) {
            return notDefinedAllocation(
                    "Allocation for Level " + subLevel + " (" + subLevelDebtLabel(subLevel)
                            + ") isn't set yet — open the rules editor from the tier card to define it.");
        }

        String[] p = {
                toPctStr(rule.getDonationPercent()), toPctStr(rule.getEmergencyPercent()),
                toPctStr(rule.getInvestmentsPercent()), toPctStr(rule.getStocksPercent())
        };
        List<AllocationLine> lines = percentLines(incomeBaseUzs, displayCurrency, paid,
                p[0], p[1], p[2], p[3]);

        List<ActionItem> actions = new ArrayList<>();
        if (bankMonthlyUzs.signum() > 0) {
            actions.add(payBank(monthPaid, fx.fromUzs(bankMonthlyUzs, displayCurrency)));
        }
        if (personalLoansRemainingUzs.signum() > 0) {
            BigDecimal personalTarget = fx.fromUzs(
                    personalLoansRemainingUzs.multiply(PERSONAL_LOAN_PAYDOWN_RATE, MC), displayCurrency);
            actions.add(payPersonal(personalTarget, displayCurrency, monthPaid, personalTarget));
        }
        if (rule.getNote() != null && !rule.getNote().isBlank()) {
            actions.add(info(rule.getNote()));
        }

        return TierAllocation.builder()
                .scenarioKey(subLevel)
                .scenarioLabel("Level " + subLevel + " — " + subLevelDebtLabel(subLevel))
                .lines(lines)
                .actions(actions)
                .allocationLocked(isAllocationLocked(actions))
                .build();
    }

    /** Percentages for a month's (level, sub-level): Level 1 hard-coded, 2–6 from config. */
    private String[] bucketPercents(Integer level, String subLevel,
            BigDecimal stableUzs, BigDecimal mandatoryUzs,
            BigDecimal bankUzs, BigDecimal personalRemUzs) {
        if (level != null && level == 1) {
            return percentsForKey(scenarioKeyFor(level, subLevel, stableUzs, mandatoryUzs, bankUzs, personalRemUzs));
        }
        if (level != null && level >= 2 && level <= 6 && subLevel != null) {
            return ruleRepository.findBySubLevel(subLevel)
                    .map(r -> new String[]{
                            toPctStr(r.getDonationPercent()), toPctStr(r.getEmergencyPercent()),
                            toPctStr(r.getInvestmentsPercent()), toPctStr(r.getStocksPercent())})
                    .orElse(new String[]{null, null, null, null});
        }
        return new String[]{null, null, null, null};
    }

    private static String toPctStr(BigDecimal pct) {
        return pct == null ? null : pct.stripTrailingZeros().toPlainString();
    }

    // ── Allocation-rule view + per-level config (Levels 2–6, Level 1 reference) ──

    /** Configured minimum leftover for a level, in UZS. Level 1 defaults to 5M. */
    private BigDecimal minLeftoverUzs(int level) {
        BigDecimal v = levelConfigRepository.findByLevel(level)
                .map(LevelConfig::getMinLeftover).orElse(null);
        if (v != null) return v;
        return FIVE_MILLION_UZS;
    }

    /** The user's current level from stable income − subscriptions (null if income unset). */
    private Integer currentLevel() {
        Settings s = settingsService.getOrCreate();
        if (s.getMonthlyStableIncome() == null || s.getMonthlyStableIncomeCurrency() == null
                || s.getMonthlyStableIncome().signum() <= 0) return null;
        BigDecimal stableUzs = fx.toUzs(s.getMonthlyStableIncome(), s.getMonthlyStableIncomeCurrency());
        return computeLevel(stableUzs.subtract(sumActiveSubscriptionsUzs()));
    }

    /** Inclusive lower / exclusive upper left-money bound (UZS) for a level. */
    private BigDecimal levelIncomeLow(int level) {
        return level <= 1 ? BigDecimal.ZERO : LEVEL_BREAKPOINTS_UZS[level - 2];
    }
    private BigDecimal levelIncomeHigh(int level) {
        return LEVEL_BREAKPOINTS_UZS[level - 1];
    }

    /** Reference percentages for Level 1's three sub-levels (read-only comparison). */
    private String[] level1Reference(String subLevel) {
        if (subLevel.endsWith(".1")) return new String[]{"10", "5", "15", "1"};
        if (subLevel.endsWith(".2")) return new String[]{"10", "5", "15", "1"};
        return new String[]{"2", null, null, null}; // .3
    }

    @Transactional(readOnly = true)
    public AllocationRulesViewResponse getAllocationRules() {
        Settings s = settingsService.getOrCreate();
        boolean missingIncome = s.getMonthlyStableIncome() == null
                || s.getMonthlyStableIncomeCurrency() == null
                || s.getMonthlyStableIncome().signum() <= 0;

        Integer curLevel = currentLevel();
        String curSubLevel = null;
        if (curLevel != null && !missingIncome) {
            BigDecimal stableUzs = fx.toUzs(s.getMonthlyStableIncome(), s.getMonthlyStableIncomeCurrency());
            LocalDate today = LocalDate.now();
            YearMonth now = YearMonth.now();
            BigDecimal debtTotal = sumBankLoanMonthlyPaymentsUzs(today)
                    .add(sumLoansTakenMonthlyUzs(today, now)).add(sumDebtsMonthlyUzs(today, now));
            BigDecimal ratio = stableUzs.signum() > 0 ? debtTotal.divide(stableUzs, MC) : null;
            curSubLevel = computeSubLevel(curLevel, debtTotal, ratio);
        }

        YearMonth thisMonth = YearMonth.now();
        List<LevelView> levels = new ArrayList<>();
        for (int level = 1; level <= 6; level++) {
            LevelConfig cfg = levelConfigRepository.findByLevel(level).orElse(null);
            LocalDate exp = cfg != null ? cfg.getExpirationMonth() : null;
            boolean locked = exp != null && thisMonth.isBefore(YearMonth.from(exp));
            boolean builtIn = level == 1;
            boolean editable = curLevel != null && curLevel == level && !locked;

            BigDecimal minLeftover = cfg != null && cfg.getMinLeftover() != null
                    ? cfg.getMinLeftover()
                    : (level == 1 ? FIVE_MILLION_UZS : null);

            List<SubLevelView> subs = new ArrayList<>(3);
            for (int sub = 1; sub <= 3; sub++) {
                String subLevel = level + "." + sub;
                String[] p = builtIn ? level1Reference(subLevel)
                        : ruleRepository.findBySubLevel(subLevel)
                            .map(r -> new String[]{toPctStr(r.getDonationPercent()), toPctStr(r.getEmergencyPercent()),
                                    toPctStr(r.getInvestmentsPercent()), toPctStr(r.getStocksPercent())})
                            .orElse(new String[]{null, null, null, null});
                subs.add(SubLevelView.builder()
                        .subLevel(subLevel)
                        .debtLabel(subLevelDebtLabel(subLevel))
                        .donationPercent(parsePct(p[0]))
                        .emergencyPercent(parsePct(p[1]))
                        .investmentsPercent(parsePct(p[2]))
                        .stocksPercent(parsePct(p[3]))
                        .build());
            }

            levels.add(LevelView.builder()
                    .level(level)
                    .incomeLow(levelIncomeLow(level))
                    .incomeHigh(levelIncomeHigh(level))
                    .minLeftover(minLeftover)
                    .expirationMonth(exp == null ? null : YearMonth.from(exp).toString())
                    .locked(locked)
                    .editable(editable)
                    .builtIn(builtIn)
                    .subLevels(subs)
                    .build());
        }

        return AllocationRulesViewResponse.builder()
                .currentLevel(curLevel)
                .currentSubLevel(curSubLevel)
                .missingStableIncome(missingIncome)
                .levels(levels)
                .build();
    }

    /**
     * Save one level's config. Only the user's CURRENT level may be saved, and only when it
     * isn't locked by an unreached expiration month. Level 1's percentages are built-in, so
     * only its minimum leftover / expiration are stored; Levels 2–6 also upsert sub-level
     * percentages (a sub-level with all-null percents is removed).
     */
    @Transactional
    public AllocationRulesViewResponse saveLevelConfig(LevelConfigRequest req) {
        Integer level = req.getLevel();
        if (level == null || level < 1 || level > 6) {
            throw new IllegalArgumentException("Level must be between 1 and 6.");
        }
        Integer cur = currentLevel();
        if (cur == null || !cur.equals(level)) {
            throw new IllegalArgumentException(
                    "You can only edit your current level" + (cur == null ? "." : " (Level " + cur + ")."));
        }
        // Reject when the level is currently locked (existing expiration not yet reached).
        LevelConfig cfg = levelConfigRepository.findByLevel(level).orElse(null);
        LocalDate existingExp = cfg != null ? cfg.getExpirationMonth() : null;
        if (existingExp != null && YearMonth.now().isBefore(YearMonth.from(existingExp))) {
            throw new IllegalArgumentException(
                    "Level " + level + " is locked until " + YearMonth.from(existingExp) + ".");
        }

        if (cfg == null) {
            cfg = new LevelConfig();
            cfg.setLevel(level);
        }
        cfg.setMinLeftover(req.getMinLeftover());
        cfg.setExpirationMonth(req.getExpirationMonth() == null ? null
                : req.getExpirationMonth().withDayOfMonth(1));
        levelConfigRepository.save(cfg);

        // Percentages: Level 1 is built-in; 2–6 upsert each provided sub-level row.
        if (level >= 2 && req.getRules() != null) {
            for (LevelAllocationRuleRequest rule : req.getRules()) {
                if (rule.getSubLevel() == null || rule.getSubLevel().isBlank()) continue;
                if (parseLevel(rule.getSubLevel()) != level) {
                    throw new IllegalArgumentException(
                            "Sub-level " + rule.getSubLevel() + " doesn't belong to Level " + level + ".");
                }
                upsertRule(rule);
            }
        }
        return getAllocationRules();
    }

    /** Upsert one sub-level's percentages; all-null → delete (reverts to "not set"). */
    private void upsertRule(LevelAllocationRuleRequest req) {
        String subLevel = req.getSubLevel();
        int level = parseLevel(subLevel);
        boolean allNull = req.getDonationPercent() == null && req.getEmergencyPercent() == null
                && req.getInvestmentsPercent() == null && req.getStocksPercent() == null;
        LevelAllocationRule existing = ruleRepository.findBySubLevel(subLevel).orElse(null);
        if (allNull) {
            if (existing != null) ruleRepository.delete(existing);
            return;
        }
        LevelAllocationRule r = existing != null ? existing : new LevelAllocationRule();
        r.setLevel(level);
        r.setSubLevel(subLevel);
        r.setDonationPercent(req.getDonationPercent());
        r.setEmergencyPercent(req.getEmergencyPercent());
        r.setInvestmentsPercent(req.getInvestmentsPercent());
        r.setStocksPercent(req.getStocksPercent());
        r.setNote(req.getNote() != null && req.getNote().isBlank() ? null : req.getNote());
        ruleRepository.save(r);
    }

    private static BigDecimal parsePct(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    /** "{level}.{sub}" → level int. */
    private int parseLevel(String subLevel) {
        try {
            return Integer.parseInt(subLevel.split("\\.")[0]);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Bad sub-level format: " + subLevel);
        }
    }

    /** Action item helpers. */
    private static ActionItem info(String text) {
        return ActionItem.builder().text(text).build();
    }

    private static ActionItem payBank(MonthPaid monthPaid, BigDecimal target) {
        return ActionItem.builder()
                .text("Pay bank installments this month.")
                .action("PAY_BANK")
                .paid(monthPaid.bankInstallments())
                .target(target)
                .unlockThreshold(bankUnlockThreshold(target))
                .build();
    }

    private static ActionItem payPersonal(BigDecimal targetDisplay, Currency displayCurrency,
                                          MonthPaid monthPaid, BigDecimal target) {
        return ActionItem.builder()
                .text("Pay at least 34% (~ " + formatNumber(targetDisplay) + " " + displayCurrency
                        + ") of personal loans this month.")
                .action("PAY_PERSONAL_LOAN")
                .paid(monthPaid.personalLoanRepayments())
                .target(target)
                // Personal loans must reach the full 34% target to unlock.
                .unlockThreshold(target)
                .build();
    }

    /** Bank installment unlock amount: 90% of the average monthly payment. */
    private static BigDecimal bankUnlockThreshold(BigDecimal target) {
        return target == null ? null : target.multiply(BANK_UNLOCK_RATE, MC);
    }

    /**
     * Whether allocation recording should be locked: true when any actionable item is still
     * below its unlock threshold. Informational items (no action key, no threshold) never lock.
     */
    private static boolean isAllocationLocked(List<ActionItem> actions) {
        return actions.stream().anyMatch(a ->
                a.getAction() != null
                        && a.getUnlockThreshold() != null
                        && nullToZero(a.getPaid()).compareTo(a.getUnlockThreshold()) < 0);
    }

    /**
     * Build the four bucket lines from minimum-percent strings. Pass null for a bucket
     * that should render as "NO NEED" at this tier. Paid-this-month amounts (already in
     * display currency) come from the BucketPaid context.
     */
    private List<AllocationLine> percentLines(BigDecimal incomeUzs, Currency displayCurrency, BucketPaid paid,
                                              String donationPct, String emergencyPct,
                                              String investmentsPct, String stocksPct) {
        List<AllocationLine> lines = new ArrayList<>(4);
        lines.add(line("DONATION",    "Donation",    donationPct,    incomeUzs, displayCurrency, paid.donation()));
        lines.add(line("EMERGENCY",   "Emergency",   emergencyPct,   incomeUzs, displayCurrency, paid.emergency()));
        lines.add(line("INVESTMENTS", "Investments", investmentsPct, incomeUzs, displayCurrency, paid.investments()));
        lines.add(line("STOCKS",      "Stocks",      stocksPct,      incomeUzs, displayCurrency, paid.stocks()));
        return lines;
    }

    private AllocationLine line(String bucket, String label, String pctStr,
                                BigDecimal incomeUzs, Currency displayCurrency, BigDecimal paidDisplay) {
        if (pctStr == null) {
            // Not recommended at this tier. Still surface paidAmount so the frontend can
            // tell the user "you paid X here even though it's not recommended this month".
            return AllocationLine.builder()
                    .bucket(bucket).label(label).recommended(false)
                    .minPercent(null).minAmount(null)
                    .paidAmount(paidDisplay).paidPercent(null).remainingAmount(null)
                    .build();
        }
        BigDecimal pct = new BigDecimal(pctStr);
        BigDecimal amountUzs = incomeUzs.multiply(pct, MC).divide(new BigDecimal("100"), MC);
        BigDecimal minAmount = fx.fromUzs(amountUzs, displayCurrency);

        BigDecimal paidPercent = minAmount.signum() > 0
                ? paidDisplay.multiply(new BigDecimal("100"), MC).divide(minAmount, MC)
                : null;
        BigDecimal remaining = minAmount.subtract(paidDisplay);
        if (remaining.signum() < 0) remaining = BigDecimal.ZERO;

        return AllocationLine.builder()
                .bucket(bucket).label(label).recommended(true)
                .minPercent(pct)
                .minAmount(minAmount)
                .paidAmount(paidDisplay)
                .paidPercent(paidPercent)
                .remainingAmount(remaining)
                .build();
    }

    private TierAllocation notDefinedAllocation(String note) {
        return TierAllocation.builder()
                .scenarioKey(null)
                .scenarioLabel("Guidance not yet defined for this tier")
                .lines(List.of())
                .actions(List.of(info(note)))
                .build();
    }

    private static String formatNumber(BigDecimal n) {
        if (n == null) return "0";
        // Cheap thousand-grouping. Detail is fine since notes are short scenario hints.
        return n.setScale(0, RoundingMode.HALF_UP).toPlainString()
                .replaceAll("(\\d)(?=(\\d{3})+$)", "$1 "); // thin space
    }
}
