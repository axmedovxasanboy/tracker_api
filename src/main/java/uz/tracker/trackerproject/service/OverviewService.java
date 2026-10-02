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
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.Donation;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.entity.LevelAllocationRule;
import uz.tracker.trackerproject.entity.LevelConfig;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.RepaymentType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.LevelAllocationRuleRepository;
import uz.tracker.trackerproject.repository.LevelConfigRepository;
import uz.tracker.trackerproject.repository.MarkPaidRepository;
import uz.tracker.trackerproject.repository.MonthlyPaymentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;
import uz.tracker.trackerproject.dto.response.AllocationRulesViewResponse;
import uz.tracker.trackerproject.dto.response.AllocationRulesViewResponse.LevelView;
import uz.tracker.trackerproject.dto.response.AllocationRulesViewResponse.SubLevelView;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OverviewService {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);
    private static final BigDecimal DEBT_RATIO_THRESHOLD = new BigDecimal("0.70");
    /**
     * Levels 1–4 from what is left after bills, in 15M steps (LEVELS-ALLOCATION-SPEC §1.1, 2026-10-01):
     * under 15M → 1, under 30M → 2, under 45M → 3, from 45M → 4 with no upper limit. Level 5 is not
     * a band: it is earned by pay (see {@link #levelOf}). There is no "above the ceiling" any more.
     */
    private static final BigDecimal[] LEVEL_BREAKPOINTS_UZS = {
            new BigDecimal("15000000"),  // < this → level 1
            new BigDecimal("30000000"),  // < this → level 2
            new BigDecimal("45000000"),  // < this → level 3
    };
    /** The highest level income − bills can give; Level 5 is above it, by pay. */
    static final int TOP_BASE_LEVEL = 4;
    /** Level 5: pay of at least this, three ended months in a row on Level 4 (and under it, three, to leave). */
    static final BigDecimal LEVEL5_PAY_THRESHOLD = new BigDecimal("60000000");
    static final int LEVEL5_MONTHS_NEEDED = 3;

    /** Default "tight vs comfortable" cutoff within Level 1.2 (5M UZS); LevelConfig can override it. */
    private static final BigDecimal FIVE_MILLION_UZS = new BigDecimal("5000000");
    /** The ASAP ask on a balance above 70% of the stable income: 34% of what is left. */
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
    private final InvestmentRepository investmentRepository;
    private final LevelAllocationRuleRepository ruleRepository;
    private final LevelConfigRepository levelConfigRepository;
    private final MarkPaidRepository markPaidRepository;
    private final SettingsService settingsService;
    private final CategoryRepository categoryRepository;

    /**
     * The levels' savings rules, version by version, and the recorded starts and ends of Level 5
     * (LEVELS-ALLOCATION-SPEC). Field-injected, so the constructor every caller uses stays as it is;
     * absent (null) where the service is built by hand, which then reads the seeded rules — Level 1's
     * table at every level, exactly as the stored first versions hold it — and no Level 5.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private uz.tracker.trackerproject.repository.LevelRuleVersionRepository levelRuleVersionRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private uz.tracker.trackerproject.repository.LevelChangeRepository levelChangeRepository;

    @Transactional(readOnly = true)
    public OverviewIncomeResponse getIncome(YearMonth month, Currency displayCurrency) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        // Sum this month's INCOME transactions in the reporting currency — a foreign cash pot
        // never rolls into a UZS figure (see Currency.reporting).
        BigDecimal actual = BigDecimal.ZERO;
        for (Currency c : Currency.reporting()) {
            BigDecimal sum = transactionRepository.sumByTypeCurrencyDateRange(
                    TransactionType.INCOME, c, start, end);
            if (sum == null || sum.signum() == 0) continue;
            actual = actual.add(sum);
        }

        // That month's income: a change recorded from a later month does not reach back.
        BigDecimal stable = stableIncomeFor(month);

        return OverviewIncomeResponse.builder()
                .month(month.toString())
                .currency(displayCurrency)
                .actualIncome(actual)
                .stableIncome(stable)
                .build();
    }

    // ── Tier ──────────────────────────────────────────────────────────────────

    /**
     * Compute the user's financial tier for a given month. The income is that month's own
     * ({@link #stableIncomeFor} — the value recorded from the latest month ≤ it); subscriptions and
     * the loan and debt balances are the CURRENT ones — no snapshots of those are stored; but which
     * obligations count (a bank loan's dates, a loan or debt's payment-start
     * month), the bonus income that raises the base, and every "paid this month" figure are
     * scoped to the requested month.
     */
    @Transactional(readOnly = true)
    public OverviewTierResponse getTier(YearMonth month, Currency displayCurrency) {
        // No day comes with a month: the month under way counts the salary received up to the
        // server's today (see salaryReceivedUzs).
        return tier(month, displayCurrency, true, true, LocalDate.now());
    }

    /**
     * The same tier with the allocation computed even while subscriptions are unpaid — for the
     * advisor, which names what is still to set aside "after the bills" instead of hiding it.
     * {@code subscriptionsPending} and {@code pendingSubscriptions} are still reported, so the
     * caller knows the bills come first.
     */
    @Transactional(readOnly = true)
    public OverviewTierResponse getTierIgnoringSubscriptions(YearMonth month, Currency displayCurrency) {
        return tier(month, displayCurrency, false, true, LocalDate.now());
    }

    /** The same, counting the salary received up to {@code asOf} — the owner's own today. */
    @Transactional(readOnly = true)
    public OverviewTierResponse getTierIgnoringSubscriptions(YearMonth month, Currency displayCurrency,
                                                             LocalDate asOf) {
        return tier(month, displayCurrency, false, true, asOf);
    }

    /**
     * The same tier for the profile, which explains the owner's configuration — the level, the
     * rule and the percentages — rather than asking for payments: the allocation is computed even
     * while subscriptions are unpaid AND before allocation tracking starts. Once tracking has
     * started it is exactly {@link #getTierIgnoringSubscriptions}, so the two quote the same amounts.
     */
    @Transactional(readOnly = true)
    public OverviewTierResponse getTierForProfile(YearMonth month, LocalDate asOf) {
        return tier(month, Currency.UZS, false, false, asOf);
    }

    /**
     * @param asOf the day the salary is counted up to in a month still under way (see
     *             {@link #salaryReceivedUzs}); a month already over counts all of its own
     */
    private OverviewTierResponse tier(YearMonth month, Currency displayCurrency, boolean subscriptionsGate,
                                      boolean trackingGate, LocalDate asOf) {
        Settings s = settingsService.getOrCreate();
        // The month's own income (STABLE-INCOME-HISTORY): the level, the rule, the cutoff and the
        // base all follow the value recorded for THIS month, so a change from a later month never
        // rewrites it.
        BigDecimal monthIncome = stableIncomeFor(month);
        boolean missingIncome = monthIncome == null || monthIncome.signum() <= 0;

        BigDecimal incomeUzs = missingIncome ? BigDecimal.ZERO : monthIncome;

        BigDecimal mandatoryUzs = sumActiveSubscriptionsUzs();
        BigDecimal leftMoneyUzs = incomeUzs.subtract(mandatoryUzs);

        // The owner's model: the only "monthly loan installment" is a BANK loan. Money borrowed from
        // a person (LoanTaken) and money owed (Debt) are BOTH personal debt: a MONTHLY loan asks its
        // plan (from its payment-start month), an ASAP loan or a debt its ASAP ask (from the month
        // it was borrowed) — see debtAsks. Their sum is the debt charge.
        BigDecimal bankUzs = sumBankLoanMonthlyPaymentsUzs(month);
        List<DebtAsk> asks = debtAsks(month, month.atEndOfMonth());
        BigDecimal loanTaken34Uzs = asks.stream().filter(a -> LOAN_ASK.equals(a.kind()))
                .map(DebtAsk::ask).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal debtRows34Uzs = asks.stream().filter(a -> DEBT_ASK.equals(a.kind()))
                .map(DebtAsk::ask).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal plannedSetAsideUzs = asks.stream().filter(a -> !a.asap())
                .map(DebtAsk::ask).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loanInstallmentsUzs = bankUzs;
        BigDecimal debt34Uzs = loanTaken34Uzs.add(debtRows34Uzs);
        BigDecimal debtPaymentsUzs = loanInstallmentsUzs.add(debt34Uzs);

        BigDecimal debtRatio = incomeUzs.signum() > 0
                ? debtPaymentsUzs.divide(incomeUzs, MC)
                : null;

        // Which borrowed money makes the savings rule lighter (the owner's rule, 2026-09-30): ASAP
        // money always; MONTHLY plans only when together they are more than 10% of the stable income.
        BigDecimal ruleDebtUzs = ruleDebtUzs(debt34Uzs, plannedSetAsideUzs, incomeUzs);

        // Levels 1–4 from the income − bills; Level 5 while a recorded Level 5 period covers the month.
        Integer level = missingIncome ? null : levelOf(month, leftMoneyUzs, levelChanges());
        String subLevel = computeSubLevel(level, bankUzs.add(ruleDebtUzs), debtRatio);
        String levelLabel = computeLevelLabel(level, subLevel, missingIncome);

        // Allocation base = the stable income + the bonus received for the month (owner's decision,
        // 2026-09-30 — see allocationBaseUzs): recording a salary or an advance never moves a target,
        // only a bonus does. The salary received is still reported — the advisor and the daily walk
        // read it — but it plays no part in the base. The level, the sub-level, the
        // tight-vs-comfortable split and the percentages stay on the stable-income anchor too.
        BigDecimal bonusUzs = sumBonusIncomeUzs(month);
        BigDecimal salaryUzs = salaryReceivedUzs(month, asOf, salaryTree());
        BigDecimal allocBaseUzs = allocationBaseUzs(incomeUzs, bonusUzs);

        // Paid-this-month per bucket (display currency), including "already paid" bucket marks.
        // The marks are also carried on their own so each line can say how much of its "paid"
        // figure never left a wallet — the one number the month view legitimately reports lower.
        BucketPaid marks = computeBucketMarks(month);
        BucketPaid paid = withBucketMarks(computePaidThisMonth(month, displayCurrency, false), marks);

        // Paid-this-month for the two debt-pay actions, so the guidance card can show
        // "you've paid X of Y this month" without the tier itself shifting.
        MonthPaid monthPaid = computeMonthPaid(month, displayCurrency);

        // Allocation tracking is dormant until the user-configured start month arrives.
        // Viewing a month before it still shows the tier (the client greys it) but asks for
        // NO payments — so setting a future start date can't demand a pay-now this month.
        YearMonth trackingStart = s.getAllocationTrackingStartMonth() != null
                ? YearMonth.from(s.getAllocationTrackingStartMonth()) : null;
        boolean beforeTrackingStart = trackingStart != null && month.isBefore(trackingStart);

        // Mandatory subscriptions come FIRST: until every active subscription is paid for the
        // viewed month, the level / sub-level / action items / allocation stay withheld. "Paid"
        // is measured from real recorded payments (monthlyPaymentId) dated this month.
        List<OverviewTierResponse.PendingSubscription> pendingSubs =
                (missingIncome || beforeTrackingStart) ? List.of() : pendingSubscriptions(month);
        boolean subscriptionsPending = !pendingSubs.isEmpty();

        TierAllocation allocation;
        if (missingIncome) {
            allocation = notDefinedAllocation("page.plan.note.setIncome", Map.of(),
                    "Set monthly income to see allocation guidance.");
        } else if (beforeTrackingStart && trackingGate) {
            allocation = notDefinedAllocation("page.plan.note.trackingStarts",
                    Map.of("month", trackingStart.toString()),
                    "Allocation tracking starts " + monthLabel(trackingStart)
                            + " — guidance is paused until then.");
        } else if (subscriptionsPending && subscriptionsGate) {
            allocation = notDefinedAllocation("page.plan.note.subscriptionsPending",
                    Map.of("month", month.toString()),
                    "Pay your mandatory subscription(s) for " + monthLabel(month)
                            + " first — your level and allocation unlock once they're covered.");
        } else {
            allocation = computeAllocation(month, ruleBook(), level, incomeUzs, allocBaseUzs, mandatoryUzs,
                    bankUzs, debt34Uzs, debtRatio, plannedSetAsideUzs,
                    displayCurrency, paid, marks, monthPaid, upcomingChargeActions(month, displayCurrency));
        }

        return OverviewTierResponse.builder()
                .currency(displayCurrency)
                .income(incomeUzs)
                .mandatorySubscriptions(mandatoryUzs)
                .leftMoney(leftMoneyUzs)
                .allocationBase(allocBaseUzs)
                .bonusIncome(bonusUzs)
                .salaryReceived(salaryUzs)
                .debtPayments(debtPaymentsUzs)
                .debtBreakdown(OverviewTierResponse.DebtBreakdown.builder()
                        .bankLoans(bankUzs)
                        .loansTaken(loanTaken34Uzs)
                        .debts(debtRows34Uzs)
                        .monthlyPlans(plannedSetAsideUzs)
                        .countedForRule(ruleDebtUzs)
                        .build())
                .debtRatio(debtRatio)
                .level(level)
                .subLevel(subLevel)
                .levelLabel(levelLabel)
                .missingStableIncome(missingIncome)
                .beforeTrackingStart(beforeTrackingStart)
                .trackingStartMonth(trackingStart == null ? null : trackingStart.toString())
                .subscriptionsPending(subscriptionsPending)
                .pendingSubscriptions(pendingSubs)
                .allocation(allocation)
                .build();
    }

    /**
     * Active subscriptions not yet fully paid for {@code month}, measured from real recorded
     * payments (transactions carrying that {@code monthlyPaymentId}, dated within the month).
     * Amounts stay in each subscription's own currency — that's what the Pay modal expects.
     *
     * <p>Package-private so {@link DailyAdviceService} asks "is this month's bill paid?" the same
     * way the Plan and the advisor's bill list do.
     */
    List<OverviewTierResponse.PendingSubscription> pendingSubscriptions(YearMonth month) {
        return pendingSubscriptions(month, month.atEndOfMonth());
    }

    /**
     * The same, counting only payments dated on or before {@code asOf}: the daily advice weighs
     * the wallets as of today, and a payment recorded for a later day has not left them yet.
     * "Already paid" marks always count — they carry a month, not a day, and move no money.
     */
    List<OverviewTierResponse.PendingSubscription> pendingSubscriptions(YearMonth month, LocalDate asOf) {
        LocalDate start = month.atDay(1);
        LocalDate end = asOf.isBefore(month.atEndOfMonth()) ? asOf : month.atEndOfMonth();
        List<OverviewTierResponse.PendingSubscription> pending = new ArrayList<>();
        for (MonthlyPayment m : monthlyPaymentRepository.findAll()) {
            if (!Boolean.TRUE.equals(m.getActive())) continue;
            if (m.getAmount() == null || m.getCurrency() == null || m.getAmount().signum() <= 0) continue;
            BigDecimal paidThisMonth = transactionRepository.sumByMonthlyPaymentIdAndDateRange(m.getId(), start, end);
            if (paidThisMonth == null) paidThisMonth = BigDecimal.ZERO;
            // Include "already paid" marks for this subscription this month (no transaction recorded).
            for (MarkPaid mk : markPaidRepository.findByKindAndRefIdAndMonth("SUBSCRIPTION", m.getId(), start)) {
                paidThisMonth = paidThisMonth.add(mk.getAmount());
            }
            if (paidThisMonth.compareTo(m.getAmount()) >= 0) continue; // fully covered this month
            pending.add(OverviewTierResponse.PendingSubscription.builder()
                    .id(m.getId())
                    .name(m.getName())
                    .currency(m.getCurrency())
                    .amount(m.getAmount())
                    .paid(paidThisMonth)
                    .build());
        }
        return pending;
    }

    // ── Allocation ledger (cross-month backlog) ────────────────────────────────

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String[] LEDGER_BUCKETS = {"DONATION", "EMERGENCY", "INVESTMENTS"};
    private static final String[] LEDGER_LABELS = {"Donation", "Emergency", "Investments"};
    /**
     * Ledger width. The percentage arrays produced by {@link #computeLevel1Plan} and
     * {@code SavingsRules.Percents#strings} are 4 wide — index 3 is the retired Stocks slot — so
     * every ledger loop must be bounded by
     * THIS, never by the length of a pct array. Hard-coding the bound is what broke the page
     * when Stocks was dropped from LEDGER_BUCKETS.
     */
    private static final int LEDGER_WIDTH = LEDGER_BUCKETS.length;

    /**
     * Running allocation ledger from the configured start month to {@code selected}. For each
     * month we recompute the tier scenario (so the % can vary as bank loans, loans and debts start
     * or end), apply it to that month's base — the stable income plus that month's
     * bonus income, the same base the tier card uses — to get the recommended amount, and
     * carry what is left unpaid by the owner's rule ({@link #carriedInto}, 2026-09-27): a month's due is
     * its target plus what earlier months carried, and an overpayment never carries forward — it
     * clears what was carried in, but never lowers a later month's target. The level stays anchored
     * to stable income.
     */
    @Transactional(readOnly = true)
    public AllocationLedgerResponse getAllocationLedger(YearMonth selected, Currency display) {
        Settings s = settingsService.getOrCreate();
        // Each month on its own income (STABLE-INCOME-HISTORY): read once, asked month by month.
        StableIncomeSchedule income = incomeSchedule();
        boolean missingIncome = !income.isSet(selected);

        // Dormant before the configured start month: the ledger shows no dues at all, so a
        // future start date never surfaces a backlog or a pay-now for an un-started period.
        YearMonth trackingStart = s.getAllocationTrackingStartMonth() != null
                ? YearMonth.from(s.getAllocationTrackingStartMonth()) : null;
        if (trackingStart != null && selected.isBefore(trackingStart)) {
            return AllocationLedgerResponse.builder()
                    .currency(display)
                    .startMonth(trackingStart.toString())
                    .selectedMonth(selected.toString())
                    .beforeTrackingStart(true)
                    .trackingStartMonth(trackingStart.toString())
                    .buckets(List.of())
                    .months(List.of())
                    .build();
        }

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

        // Mandatory subscriptions gate the tier's whole allocation (getTier), so they must gate
        // the ledger's dues too — otherwise the Overview header prints a concrete monthly ask
        // directly above the cards saying guidance is unavailable. Called once, never per month.
        if (!pendingSubscriptions(selected).isEmpty()) {
            return AllocationLedgerResponse.builder()
                    .currency(display)
                    .startMonth(start.toString())
                    .selectedMonth(selected.toString())
                    .subscriptionsPending(true)
                    .buckets(List.of())
                    .months(List.of())
                    .build();
        }

        BigDecimal mandatoryUzs = sumActiveSubscriptionsUzs();
        BigDecimal stableUzs = nullToZero(income.amountFor(selected));
        List<uz.tracker.trackerproject.entity.LevelChange> changes = levelChanges();
        SavingsRules.Book book = ruleBook();
        Integer level = levelOf(selected, stableUzs.subtract(mandatoryUzs), changes);

        // The owner's carry rule (see carriedInto): what a month leaves unpaid of target + carried is
        // carried into the next; an overpayment never carries.
        BigDecimal[] carried = zeros();
        YearMonth[] chainStart = new YearMonth[LEDGER_WIDTH];
        BucketMonth sel = null;
        BigDecimal[] carriedSelected = zeros();

        List<MonthBreakdown> months = new ArrayList<>();
        LocalDate today = LocalDate.now();
        Set<Long> salaryTree = salaryTree();
        for (YearMonth m = start; !m.isAfter(selected); m = m.plusMonths(1)) {
            BigDecimal stableM = nullToZero(income.amountFor(m));
            Integer levelM = levelOf(m, stableM.subtract(mandatoryUzs), changes);
            BucketMonth bm = bucketMonth(m, stableM, mandatoryUzs, levelM, book, salaryTree, today);
            boolean isSelected = m.equals(selected);
            boolean monthHasActivity = false;
            List<MonthBucketLine> lines = new ArrayList<>(LEDGER_WIDTH);
            for (int b = 0; b < LEDGER_WIDTH; b++) {
                BigDecimal net = bm.target()[b].subtract(bm.paid()[b]);
                if (bm.target()[b].signum() > 0 || bm.paid()[b].signum() > 0) {
                    monthHasActivity = true;
                    lines.add(MonthBucketLine.builder()
                            .bucket(LEDGER_BUCKETS[b])
                            .percent(bm.pct()[b] == null ? null : new BigDecimal(bm.pct()[b]))
                            .recommended(bm.target()[b])
                            .paid(bm.paid()[b])
                            .net(net)
                            .build());
                }
                if (isSelected) {
                    carriedSelected[b] = carried[b];
                } else {
                    BigDecimal next = clampZero(bm.target()[b].add(carried[b]).subtract(bm.paid()[b]));
                    if (next.signum() > 0 && carried[b].signum() == 0) chainStart[b] = m;
                    if (next.signum() == 0) chainStart[b] = null;
                    carried[b] = next;
                }
            }
            if (isSelected) sel = bm;
            if (monthHasActivity) {
                months.add(MonthBreakdown.builder()
                        .month(m.toString())
                        .level(levelM)
                        .subLevel(bm.subLevel())
                        .stableIncome(stableM)
                        .bonus(bm.bonus())
                        .allocationBase(bm.base())
                        .selected(isSelected)
                        .lines(lines)
                        .build());
            }
        }

        // Effective-% denominator is the selected month's allocation base.
        BigDecimal incomeBaseSelUzs = sel.base();
        List<BucketLedger> buckets = new ArrayList<>(LEDGER_WIDTH);
        BigDecimal totalDueNowUzs = BigDecimal.ZERO;
        BigDecimal carriedPrevUzs = BigDecimal.ZERO;
        BigDecimal dueThisMonthUzs = BigDecimal.ZERO;
        YearMonth earliestDue = null;
        YearMonth latestDue = null;

        for (int b = 0; b < LEDGER_WIDTH; b++) {
            BigDecimal target = sel.target()[b];
            BigDecimal paid = sel.paid()[b];
            BigDecimal outstanding = clampZero(target.add(carriedSelected[b]).subtract(paid));
            BigDecimal effPct = (paid.signum() > 0 && incomeBaseSelUzs.signum() > 0)
                    ? paid.multiply(HUNDRED, MC).divide(incomeBaseSelUzs, MC)
                    : null;
            boolean over = paid.compareTo(target.add(carriedSelected[b])) > 0;

            buckets.add(BucketLedger.builder()
                    .bucket(LEDGER_BUCKETS[b])
                    .label(LEDGER_LABELS[b])
                    .percent(sel.pct()[b] == null ? null : new BigDecimal(sel.pct()[b]))
                    .recommended(target)
                    .paid(paid)
                    .marked(sel.marked()[b])
                    .carried(carriedSelected[b])
                    .outstanding(outstanding)
                    .effectivePercent(effPct)
                    .overAllocated(over)
                    .build());

            totalDueNowUzs = totalDueNowUzs.add(outstanding);
            carriedPrevUzs = carriedPrevUzs.add(carriedSelected[b]);
            dueThisMonthUzs = dueThisMonthUzs.add(target);
            if (carriedSelected[b].signum() > 0) {
                if (earliestDue == null || (chainStart[b] != null && chainStart[b].isBefore(earliestDue))) {
                    earliestDue = chainStart[b];
                }
                latestDue = selected.minusMonths(1);
            }
        }
        BigDecimal bonusSelectedUzs = sel.bonus();
        BigDecimal allocBaseSelectedUzs = sel.base();
        String subLevelSelected = sel.subLevel();

        return AllocationLedgerResponse.builder()
                .currency(display)
                .startMonth(start.toString())
                .selectedMonth(selected.toString())
                .missingStableIncome(false)
                .stableIncome(stableUzs)
                .bonusThisMonth(bonusSelectedUzs)
                .allocationBase(allocBaseSelectedUzs)
                .level(level)
                .subLevel(subLevelSelected)
                .dueThisMonth(dueThisMonthUzs)
                .carriedFromPrevious(carriedPrevUzs)
                .totalDueNow(totalDueNowUzs)
                .carriedStartMonth(earliestDue == null ? null : earliestDue.toString())
                .carriedEndMonth(latestDue == null ? null : latestDue.toString())
                .buckets(buckets)
                .months(months)
                .build();
    }

    // ── The monthly income, month by month ───────────────────────────────────

    /**
     * The monthly income month by month (STABLE-INCOME-HISTORY), read once. Without an answer from
     * Settings — a service built by hand — one value for every month: Settings' single income, which
     * is how the engine behaved before the history existed.
     */
    StableIncomeSchedule incomeSchedule() {
        StableIncomeSchedule schedule = settingsService.stableIncomeSchedule();
        if (schedule != null) return schedule;
        Settings s = settingsService.getOrCreate();
        return StableIncomeSchedule.single(s == null ? null : s.getMonthlyStableIncome());
    }

    /**
     * {@code month}'s monthly income — the single source every month-scoped figure reads: the
     * value recorded from the latest month ≤ {@code month}. Null when none is set.
     */
    BigDecimal stableIncomeFor(YearMonth month) {
        return incomeSchedule().amountFor(month);
    }

    // ── Carry-over of unpaid savings ──────────────────────────────────────────

    /**
     * One month of the savings rule: its sub-level and each bucket's percentage, its allocation base,
     * each bucket's target (percentage × base) and what was paid into it (marks included).
     */
    private record BucketMonth(String subLevel, String[] pct, BigDecimal bonus, BigDecimal base,
                               BigDecimal[] target, BigDecimal[] paid, BigDecimal[] marked) {}

    /**
     * {@code m}'s figures as the ledger has always rebuilt them: that month's bank loans and debt
     * asks pick its sub-level and situation, and the month's level's rules version in force then
     * gives the percentages; its income and bonus make its base.
     */
    private BucketMonth bucketMonth(YearMonth m, BigDecimal stableUzs, BigDecimal mandatoryUzs, Integer level,
                                    SavingsRules.Book book, Set<Long> salaryTree, LocalDate today) {
        // Each month is charged the bank loans that ran in IT. Asking about today instead charged
        // July for a loan taken in September, and dropped a paid-off loan from the months it was
        // still being paid in.
        BigDecimal bankUzs = sumBankLoanMonthlyPaymentsUzs(m);
        List<DebtAsk> asks = debtAsks(m, m.atEndOfMonth());
        BigDecimal debt34Uzs = asks.stream().map(DebtAsk::ask).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal monthlyUzs = asks.stream().filter(a -> !a.asap())
                .map(DebtAsk::ask).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ruleDebtUzs = ruleDebtUzs(debt34Uzs, monthlyUzs, stableUzs);
        BigDecimal debtPaymentsUzs = bankUzs.add(debt34Uzs);
        BigDecimal debtRatio = stableUzs.signum() > 0 ? debtPaymentsUzs.divide(stableUzs, MC) : null;
        String subLevel = stableUzs.signum() <= 0 ? null : computeSubLevel(level, bankUzs.add(ruleDebtUzs), debtRatio);
        // The month's level's version in force that month — its cutoff splits, its numbers ask.
        String[] pct;
        if (stableUzs.signum() <= 0 || level == null) {
            pct = new String[LEDGER_WIDTH];                                    // no income that month: nothing asked
        } else {
            SavingsRules.Version v = book.version(level, m);
            SavingsRules.Pick pick = SavingsRules.pick(stableUzs, mandatoryUzs, bankUzs, BigDecimal.ZERO,
                    debt34Uzs, ruleDebtUzs, debtRatio, v.cutoff());
            pct = v.percents(pick.situation()).strings();
        }
        BigDecimal bonusUzs = sumBonusIncomeUzs(m);
        BigDecimal base = allocationBaseUzs(stableUzs, bonusUzs);
        // Marks read once per month and folded in here, so the loop never queries them twice.
        BucketPaid marks = computeBucketMarks(m);
        BucketPaid paid = withBucketMarks(computePaidThisMonth(m, Currency.UZS, false), marks);
        BigDecimal[] target = zeros();
        for (int b = 0; b < LEDGER_WIDTH; b++) {
            if (pct[b] != null) target[b] = base.multiply(new BigDecimal(pct[b]), MC).divide(HUNDRED, MC);
        }
        return new BucketMonth(subLevel, pct, bonusUzs, base, target,
                new BigDecimal[]{paid.donation(), paid.emergency(), paid.investments()},
                new BigDecimal[]{marks.donation(), marks.emergency(), marks.investments()});
    }

    /**
     * What each bucket (DONATION, EMERGENCY, INVESTMENTS) carries into {@code month} from the months
     * before it, by the owner's rule (2026-09-27), from the tracking start:
     * <pre>
     *   due_m          = target_m + carried_m
     *   carried_{m+1}  = max(0, due_m − paid_m)          carried = 0 at the tracking start
     * </pre>
     * An overpayment never carries: 2.5M paid against a 2M target leaves nothing owed, and does not
     * lower next month's target. Savings goals are not carried — they keep their own schedule. All
     * zero without a stable income or a tracking start, and for a month not after the start.
     */
    Map<String, BigDecimal> carriedInto(YearMonth month) {
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (String b : LEDGER_BUCKETS) out.put(b, BigDecimal.ZERO);
        Settings s = settingsService.getOrCreate();
        StableIncomeSchedule income = incomeSchedule();
        if (s == null || !income.isSet(month) || s.getAllocationTrackingStartMonth() == null) {
            return out;
        }
        YearMonth start = YearMonth.from(s.getAllocationTrackingStartMonth());
        if (!month.isAfter(start)) return out;
        BigDecimal mandatoryUzs = sumActiveSubscriptionsUzs();
        Set<Long> salaryTree = salaryTree();
        LocalDate today = LocalDate.now();
        List<uz.tracker.trackerproject.entity.LevelChange> changes = levelChanges();
        SavingsRules.Book book = ruleBook();
        BigDecimal[] carried = zeros();
        for (YearMonth m = start; m.isBefore(month); m = m.plusMonths(1)) {
            // Each month's own income: what it asked, and so what it left unpaid, never moves when
            // the income changes from a later month.
            BigDecimal stableM = nullToZero(income.amountFor(m));
            BucketMonth bm = bucketMonth(m, stableM, mandatoryUzs, levelOf(m, stableM.subtract(mandatoryUzs), changes),
                    book, salaryTree, today);
            for (int b = 0; b < LEDGER_WIDTH; b++) {
                carried[b] = clampZero(bm.target()[b].add(carried[b]).subtract(bm.paid()[b]));
            }
        }
        for (int b = 0; b < LEDGER_WIDTH; b++) out.put(LEDGER_BUCKETS[b], carried[b]);
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A ledger-width accumulator seeded with zeros. */
    private static BigDecimal[] zeros() {
        BigDecimal[] a = new BigDecimal[LEDGER_WIDTH];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private BigDecimal sumActiveSubscriptionsUzs() {
        BigDecimal total = BigDecimal.ZERO;
        for (MonthlyPayment m : monthlyPaymentRepository.findAll()) {
            if (!Boolean.TRUE.equals(m.getActive())) continue;
            if (m.getAmount() == null || m.getCurrency() == null) continue;
            total = total.add(m.getAmount());
        }
        return total;
    }

    // ── The allocation base: what the percentages multiply ─────────────────────

    /**
     * The allocation base, decided by the owner on 2026-09-30 ("allocations should update only when
     * bonus income is added"):
     *
     * <pre>allocationBase = stable income (Settings) + bonus received for the month</pre>
     *
     * The targets are known from the month's first day and never move when a salary or an advance
     * is recorded — however many, however large. Only a bonus (counted in its accounting month,
     * Transaction.salaryMonth) raises them. History: 2026-09-23 to 2026-09-30 it was max(stable income,
     * salary received) + bonus; before that max(0, stable − subscriptions − debt charge) + bonus.
     */
    static BigDecimal allocationBaseUzs(BigDecimal stableUzs, BigDecimal bonusUzs) {
        return nullToZero(stableUzs).add(nullToZero(bonusUzs));
    }

    /**
     * The categories the salary is recorded in — the SALARY TREE — as ids; null when there is none,
     * and then every non-bonus regular income counts as salary.
     * <ol>
     *   <li>The root category above the bonus-flagged categories, with all its descendants — the
     *       owner's Salary → {Salary, Avans, Bonus}. A bonus category that is a root itself names no
     *       tree.</li>
     *   <li>Else the root income category named "Salary" (any case), with its descendants.</li>
     *   <li>Else no tree.</li>
     * </ol>
     * "Other income", freelance or an investment's return are therefore not salary unless they are
     * recorded under the salary's root.
     */
    Set<Long> salaryTree() {
        return salaryTree(categoryRepository.findAll());
    }

    /** {@link #salaryTree()} over these categories (all of them). */
    static Set<Long> salaryTree(List<Category> all) {
        if (all == null) return null;
        Set<Long> roots = new java.util.HashSet<>();
        for (Category c : all) {
            if (Boolean.TRUE.equals(c.getBonusIncome()) && c.getParent() != null) {
                Long root = rootOf(c).getId();
                if (root != null) roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            for (Category c : all) {
                if (c.getParent() == null && c.getType() != CategoryType.EXPENSE && c.getId() != null
                        && c.getName() != null && "salary".equalsIgnoreCase(c.getName().trim())) {
                    roots.add(c.getId());
                }
            }
        }
        if (roots.isEmpty()) return null;
        Set<Long> tree = new java.util.HashSet<>();
        for (Category c : all) {
            if (c.getId() != null && roots.contains(rootOf(c).getId())) tree.add(c.getId());
        }
        return tree;
    }

    /** The top of a category's tree (a guard against a parent cycle in bad data). */
    static Category rootOf(Category c) {
        Category at = c;
        for (int depth = 0; at.getParent() != null && depth < 32; depth++) at = at.getParent();
        return at;
    }

    /** A bonus category: flagged itself, or under a flagged parent — the test the bonus sum's query applies. */
    static boolean isBonusCategory(Category c) {
        return c != null && (Boolean.TRUE.equals(c.getBonusIncome())
                || (c.getParent() != null && Boolean.TRUE.equals(c.getParent().getBonusIncome())));
    }

    /** Whether a row of income is salary: in the salary tree (any category without one), never a bonus. */
    static boolean isSalaryCategory(Category c, Set<Long> tree) {
        if (isBonusCategory(c)) return false;
        return tree == null || (c != null && c.getId() != null && tree.contains(c.getId()));
    }

    /**
     * The salary received for {@code month}: REGULAR_INCOME in the salary tree, bonus categories left
     * out, UZS only (transfers, loans and loans paid back are other sub-types) — counted in its
     * accounting month ({@link #regularIncomeFor}): September's salary paid on 3 October is
     * September's. Only rows dated up to {@code asOf} count — money recorded for a later day has not
     * come yet; a month that has not begun counts nothing.
     */
    BigDecimal salaryReceivedUzs(YearMonth month, LocalDate asOf) {
        return salaryReceivedUzs(month, asOf, salaryTree());
    }

    private BigDecimal salaryReceivedUzs(YearMonth month, LocalDate asOf, Set<Long> tree) {
        BigDecimal total = BigDecimal.ZERO;
        for (Transaction t : regularIncomeFor(month)) {
            if (t.getType() != TransactionType.INCOME || t.getAmount() == null) continue;
            if (t.getCurrency() != null && t.getCurrency() != Currency.UZS) continue;
            if (asOf != null && t.getTransactionDate() != null && t.getTransactionDate().isAfter(asOf)) continue;
            if (isSalaryCategory(t.getCategory(), tree)) total = total.add(t.getAmount());
        }
        return total;
    }

    /**
     * Regular income that counts in {@code month} (its accounting month, Transaction.salaryMonth):
     * dated in it unless marked as another month's salary, plus what is marked as its salary
     * whatever day it arrived. Package-private: the profile reads the same rows.
     */
    List<Transaction> regularIncomeFor(YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<Transaction> out = new ArrayList<>();
        List<Transaction> dated = transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                uz.tracker.trackerproject.enums.TransactionSubType.REGULAR_INCOME, start, end);
        for (Transaction t : dated == null ? List.<Transaction>of() : dated) {
            if (t.getSalaryMonth() == null || t.getSalaryMonth().equals(start)) out.add(t);
        }
        List<Transaction> marked = transactionRepository.findBySalaryMonth(start);
        for (Transaction t : marked == null ? List.<Transaction>of() : marked) {
            if (t.getSubType() != uz.tracker.trackerproject.enums.TransactionSubType.REGULAR_INCOME) continue;
            LocalDate on = t.getTransactionDate();
            if (on != null && (on.isBefore(start) || on.isAfter(end))) out.add(t);   // dated elsewhere
        }
        return out;
    }

    /**
     * Bonus-tagged income received for {@code month}, in the reporting currency: INCOME in a category
     * flagged {@code bonusIncome}, or under a flagged parent — counted in its accounting month (a
     * bonus marked as August's is August's). Added to that month's allocation base.
     */
    private BigDecimal sumBonusIncomeUzs(YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        BigDecimal total = BigDecimal.ZERO;
        for (Currency c : Currency.reporting()) {
            BigDecimal sum = transactionRepository.sumBonusIncomeByCurrencyDateRange(c, start, end);
            if (sum != null && sum.signum() != 0) {
                total = total.add(sum);
            }
        }
        return total;
    }

    /**
     * The bank installments due in {@code month}: every bank loan that ran during it — taken on or
     * before its last day, and not ended before its first. A loan that ends mid-month still owes
     * that month's installment.
     */
    private BigDecimal sumBankLoanMonthlyPaymentsUzs(YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (BankLoan b : bankLoanRepository.findAll()) {
            if (b.getMonthlyPayment() == null || b.getMonthlyPayment().signum() <= 0) continue;
            if (!bankLoanRunsIn(b, month)) continue;
            total = total.add(b.getMonthlyPayment());
        }
        return total;
    }

    /**
     * Whether a bank loan owes an installment in {@code month}: taken on or before its last day,
     * and not ended before its first. The one test the Plan and the daily advice both use.
     */
    static boolean bankLoanRunsIn(BankLoan b, YearMonth month) {
        if (b.getTakenDate() != null && b.getTakenDate().isAfter(month.atEndOfMonth())) return false;
        return b.getEndDate() == null || !b.getEndDate().isBefore(month.atDay(1));
    }

    // ── Borrowed money and debts: MONTHLY plans and ASAP asks ─────────────────

    /** An ASAP loan left at or under this share of the stable income is asked in full. */
    private static final BigDecimal ASAP_ALL_SHARE = new BigDecimal("0.70");

    /** {@link DebtAsk#kind()} of borrowed money (LoanTaken) and of a debt (Debt) — ids of the two collide. */
    static final String LOAN_ASK = "LOAN";
    static final String DEBT_ASK = "DEBT";

    /**
     * One borrowed loan (LOAN) or debt (DEBT) in a month, as the Plan, the ledger, the daily walk and
     * the advisor all see it (the owner's two kinds, 2026-09-23):
     * <ul>
     *   <li>MONTHLY — the plan, from the payment-start month, capped at what is left;</li>
     *   <li>ASAP (every debt, and borrowed money without a plan) — {@link #asapAsk} on what was left at
     *       the month's start, from the month it was borrowed; the payment-start month plays no
     *       part.</li>
     * </ul>
     *
     * @param ask    what the month asks; zero in a month it does not count in
     * @param repaid repaid toward it in the month up to the as-of day: repayments linked to it and
     *               its "already paid" marks for the month
     * @param left   what is still owed now
     * @param plan   a MONTHLY loan's monthly payment; null for ASAP
     */
    record DebtAsk(String kind, Long id, String name, RepaymentType type, BigDecimal ask, BigDecimal repaid,
                   BigDecimal left, BigDecimal plan, LocalDate paymentStartDate, LocalDate borrowedDate) {
        boolean asap() {
            return type == RepaymentType.ASAP;
        }

        /** What the month still asks after its repayments. */
        BigDecimal due() {
            return clampZero(ask.subtract(repaid));
        }
    }

    /**
     * The ASAP ask on {@code leftAtStart}, what was left at a month's start: all of it when that is
     * at most 70% of the stable income, else 34% of it (to the sum). The owner's 10,000,000 on a
     * 7,000,000 salary: 3,400,000, then 2,244,000 (34% of 6,600,000), then the last 4,356,000.
     */
    static BigDecimal asapAsk(BigDecimal leftAtStart, BigDecimal stableIncome) {
        if (leftAtStart == null || leftAtStart.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal all = nullToZero(stableIncome).multiply(ASAP_ALL_SHARE);
        if (leftAtStart.compareTo(all) <= 0) return leftAtStart;
        return leftAtStart.multiply(PERSONAL_LOAN_PAYDOWN_RATE).setScale(0, RoundingMode.HALF_UP);
    }

    /** Whether money borrowed on {@code borrowedDate} is owed in {@code month}: from its own month on. */
    static boolean borrowedBy(LocalDate borrowedDate, YearMonth month) {
        return borrowedDate == null || !YearMonth.from(borrowedDate).isAfter(month);
    }

    /**
     * Every borrowed loan and debt still owed (UZS), with its ask for {@code month} and what was repaid
     * toward it by {@code asOf} (the month's end when later). One that is cleared is out of every
     * month, as before. Package-private: the daily walk and the advisor read the same asks.
     */
    List<DebtAsk> debtAsks(YearMonth month, LocalDate asOf) {
        // The 70% line of the ASAP rule is the month's own income.
        BigDecimal stable = nullToZero(stableIncomeFor(month));
        LocalDate start = month.atDay(1);
        LocalDate end = asOf == null || asOf.isAfter(month.atEndOfMonth()) ? month.atEndOfMonth() : asOf;
        List<DebtAsk> asks = new ArrayList<>();
        for (LoanTaken l : loanTakenRepository.findAll()) {
            BigDecimal left = nullToZero(l.getTotalAmount()).subtract(nullToZero(l.getPaidAmount()));
            if (left.signum() <= 0 || l.getStatus() == RecordStatus.PAID || !isUzs(l.getCurrency())) continue;
            RepaymentType type = l.effectiveRepaymentType();
            List<Repaid> repaid = repaid(transactionRepository.findByRepaidLoanTakenIdOrderByTransactionDateDesc(l.getId()),
                    markPaidRepository.findByKindAndRefId("PERSONAL_LOAN", l.getId()));
            BigDecimal ask;
            if (type == RepaymentType.MONTHLY) {
                ask = hasStartedBy(l.getPaymentStartDate(), month) ? l.getPlannedMonthlyPayment().min(left) : BigDecimal.ZERO;
            } else {
                ask = borrowedBy(l.getBorrowedDate(), month) ? asapAsk(left.add(repaidSince(repaid, start)), stable) : BigDecimal.ZERO;
            }
            asks.add(new DebtAsk(LOAN_ASK, l.getId(), l.getLenderName(), type, ask, repaidIn(repaid, start, end), left,
                    type == RepaymentType.MONTHLY ? l.getPlannedMonthlyPayment() : null,
                    l.getPaymentStartDate(), l.getBorrowedDate()));
        }
        for (Debt d : debtRepository.findAll()) {
            BigDecimal left = nullToZero(d.getTotalAmount()).subtract(nullToZero(d.getPaidAmount()));
            if (left.signum() <= 0 || d.getStatus() == RecordStatus.PAID || !isUzs(d.getCurrency())) continue;
            List<Repaid> repaid = repaid(transactionRepository.findByRepaidDebtIdOrderByTransactionDateDesc(d.getId()),
                    markPaidRepository.findByKindAndRefId("DEBT", d.getId()));
            BigDecimal ask = borrowedBy(d.getBorrowedDate(), month)
                    ? asapAsk(left.add(repaidSince(repaid, start)), stable) : BigDecimal.ZERO;
            asks.add(new DebtAsk(DEBT_ASK, d.getId(), d.getCreditorName(), RepaymentType.ASAP, ask,
                    repaidIn(repaid, start, end), left, null, d.getPaymentStartDate(), d.getBorrowedDate()));
        }
        return asks;
    }

    // ── Every loan still open: the Loans header (advisor `owe`) and Analytics' position ──

    /** A plan that would run longer than this has no payoff month worth naming. */
    private static final long MAX_PAYOFF_MONTHS = 1200;

    /**
     * One loan not yet paid off, as of a day.
     *
     * @param kind      BANK | LOAN | DEBT — the words the advisor's {@code upcoming.kind} uses
     * @param asap      repaid as fast as possible: every debt and borrowed money without a plan;
     *                  never a bank loan
     * @param original  what was borrowed
     * @param left      what is still to repay; null when it cannot be known — a bank loan keeps no
     *                  paid total, so one without an end date has no knowable remainder
     * @param monthly   the plan / the bank's monthly payment; null for ASAP money
     * @param paidOffBy the month the last payment falls in; null when it cannot be known
     */
    record OpenLoan(String kind, Long refId, String name, boolean asap, BigDecimal original,
                    BigDecimal left, BigDecimal monthly, YearMonth paidOffBy) {}

    /**
     * Every loan not yet paid off as of {@code asOf} (UZS), largest {@code left} first, unknown last:
     * the borrowed money and debts the Plan still counts ({@link #debtAsks} — a cleared one is on
     * nobody's list) and the bank loans not yet ended. One list for the advisor's {@code owe} and
     * Analytics' {@code position.loans}, so the two pages can never disagree.
     *
     * <p>A bank loan's {@code left} is known only with an end date: this month's installment as far
     * as the Plan does not count it paid (installments recorded plus "already paid" marks, applied to
     * the loans in due order — the daily walk's rule), plus one installment for every later month it
     * runs in. Once that reaches zero the loan is paid off and leaves the list.
     */
    List<OpenLoan> openLoans(LocalDate asOf) {
        YearMonth current = YearMonth.from(asOf);
        List<OpenLoan> loans = new ArrayList<>();

        Map<Long, BigDecimal> borrowed = new java.util.HashMap<>();
        for (LoanTaken l : loanTakenRepository.findAll()) borrowed.put(l.getId(), l.getTotalAmount());
        Map<Long, BigDecimal> owed = new java.util.HashMap<>();
        for (Debt d : debtRepository.findAll()) owed.put(d.getId(), d.getTotalAmount());
        for (DebtAsk a : debtAsks(current, asOf)) {
            boolean loan = LOAN_ASK.equals(a.kind());
            loans.add(new OpenLoan(a.kind(), a.id(), a.name(), a.asap(),
                    (loan ? borrowed : owed).get(a.id()), a.left(), a.asap() ? null : a.plan(),
                    paidOffBy(a, current)));
        }

        List<BankLoan> banks = new ArrayList<>();
        for (BankLoan b : bankLoanRepository.findAll()) if (isUzs(b.getCurrency())) banks.add(b);
        banks.sort(Comparator.comparing(DailyAdviceService::installmentDay)
                .thenComparing(BankLoan::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        MonthPaid monthPaid = computeMonthPaid(current, Currency.UZS, asOf);
        BigDecimal paid = monthPaid == null ? BigDecimal.ZERO : nullToZero(monthPaid.bankInstallments());
        for (BankLoan b : banks) {
            boolean hasPayment = b.getMonthlyPayment() != null && b.getMonthlyPayment().signum() > 0;
            BigDecimal monthly = hasPayment ? b.getMonthlyPayment() : null;
            BigDecimal thisMonth = BigDecimal.ZERO;
            if (hasPayment && bankLoanRunsIn(b, current)) {
                BigDecimal covered = paid.min(monthly);
                paid = paid.subtract(covered);
                thisMonth = monthly.subtract(covered);
            }
            if (b.getEndDate() != null && b.getEndDate().isBefore(current.atDay(1))) continue; // ended
            BigDecimal left = null;
            if (hasPayment && b.getEndDate() != null) {
                left = thisMonth;
                YearMonth end = YearMonth.from(b.getEndDate());
                for (YearMonth m = current.plusMonths(1); !m.isAfter(end); m = m.plusMonths(1)) {
                    if (bankLoanRunsIn(b, m)) left = left.add(monthly);
                }
                if (left.signum() <= 0) continue; // its last installment is paid
            }
            loans.add(new OpenLoan(DailyAdviceService.BANK, b.getId(), DailyAdviceService.bankName(b), false,
                    b.getTotalAmount(), left, monthly, b.getEndDate() == null ? null : YearMonth.from(b.getEndDate())));
        }
        loans.sort(Comparator.comparing(OpenLoan::left, Comparator.nullsLast(Comparator.reverseOrder())));
        return loans;
    }

    /**
     * The month a MONTHLY loan's last payment falls in, counting what is left down by the plan from
     * its next payment month: this month while it still asks something, the month after once it is
     * paid, or the plan's start month when that is still ahead. Null for ASAP money — there is no
     * schedule to count along.
     */
    static YearMonth paidOffBy(DebtAsk a, YearMonth current) {
        if (a.asap() || a.plan() == null || a.plan().signum() <= 0 || a.left() == null || a.left().signum() <= 0) {
            return null;
        }
        YearMonth first;
        BigDecimal firstPays;
        if (!hasStartedBy(a.paymentStartDate(), current)) {
            first = YearMonth.from(a.paymentStartDate());
            firstPays = a.plan().min(a.left());
        } else if (a.due().signum() > 0) {
            first = current;
            firstPays = a.due().min(a.left());
        } else {
            first = current.plusMonths(1);
            firstPays = a.plan().min(a.left());
        }
        BigDecimal after = a.left().subtract(firstPays);
        BigDecimal more = after.signum() <= 0 ? BigDecimal.ZERO : after.divide(a.plan(), 0, RoundingMode.CEILING);
        if (more.compareTo(BigDecimal.valueOf(MAX_PAYOFF_MONTHS)) > 0) return null;
        return first.plusMonths(more.longValue());
    }

    /**
     * Loan repayments in {@code month} up to {@code asOf} that name no loan or debt (UZS): the Plan
     * counts them toward the ASAP asks, so the daily walk and the advisor take them off those asks
     * in turn.
     */
    BigDecimal unlinkedRepayments(YearMonth month, LocalDate asOf) {
        LocalDate start = month.atDay(1);
        LocalDate end = asOf == null || asOf.isAfter(month.atEndOfMonth()) ? month.atEndOfMonth() : asOf;
        if (end.isBefore(start)) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        List<Transaction> rows = transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                uz.tracker.trackerproject.enums.TransactionSubType.LOAN_REPAYMENT, start, end);
        for (Transaction t : rows == null ? List.<Transaction>of() : rows) {
            if (t.getAmount() == null || t.getRepaidLoanTakenId() != null || t.getRepaidDebtId() != null) continue;
            if (!isUzs(t.getCurrency())) continue;
            total = total.add(t.getAmount());
        }
        return total;
    }

    /** One repayment toward a loan or debt: a linked repayment transaction (on its day) or a mark (for its month). */
    private record Repaid(LocalDate on, BigDecimal amount, boolean mark) {}

    private static List<Repaid> repaid(List<Transaction> repayments, List<MarkPaid> marks) {
        List<Repaid> out = new ArrayList<>();
        for (Transaction t : repayments == null ? List.<Transaction>of() : repayments) {
            if (t.getAmount() == null || t.getTransactionDate() == null) continue;
            if (t.getSubType() != uz.tracker.trackerproject.enums.TransactionSubType.LOAN_REPAYMENT) continue;
            out.add(new Repaid(t.getTransactionDate(), t.getAmount(), false));
        }
        for (MarkPaid m : marks == null ? List.<MarkPaid>of() : marks) {
            if (m.getAmount() == null || m.getMonth() == null) continue;
            out.add(new Repaid(m.getMonth().withDayOfMonth(1), m.getAmount(), true));
        }
        return out;
    }

    /** Repaid on or after {@code start} — what a balance at the month's start adds back to today's. */
    private static BigDecimal repaidSince(List<Repaid> repaid, LocalDate start) {
        return repaid.stream().filter(r -> !r.on().isBefore(start))
                .map(Repaid::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Repaid within [start, end]; a mark counts for its whole month, as the Plan counts it. */
    private static BigDecimal repaidIn(List<Repaid> repaid, LocalDate start, LocalDate end) {
        return repaid.stream()
                .filter(r -> r.mark() ? r.on().equals(start) : !r.on().isBefore(start) && !r.on().isAfter(end))
                .map(Repaid::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Repayment plans that exist but have not STARTED yet, as informational action items.
     *
     * <p>A MONTHLY loan whose payment-start month is still in the future is excluded from every
     * figure on this page. That is correct, but silently correct: the user sets a 500,000/mo plan,
     * sees nothing appear anywhere, and reasonably concludes the plan was not saved. Naming the money
     * and the month it begins costs one line. (ASAP money counts from the month it was borrowed, so
     * it never waits.)
     *
     * <p>These carry no {@code action}, so they never lock the allocation or ask to be paid.
     */
    private List<ActionItem> upcomingChargeActions(YearMonth month, Currency cur) {
        List<ActionItem> upcoming = new ArrayList<>();
        for (LoanTaken l : loanTakenRepository.findAll()) {
            if (l.effectiveRepaymentType() != RepaymentType.MONTHLY) continue;
            LocalDate startsOn = l.getPaymentStartDate();
            if (startsOn == null || hasStartedBy(startsOn, month)) continue;
            BigDecimal left = nullToZero(l.getTotalAmount()).subtract(nullToZero(l.getPaidAmount()));
            if (left.signum() <= 0) continue;
            BigDecimal charge = l.getPlannedMonthlyPayment().min(left);
            String name = orEmpty(l.getLenderName());
            String amount = formatNumber(charge) + " " + cur;
            YearMonth starts = YearMonth.from(startsOn);
            upcoming.add(info("page.plan.note.loanPlanNotStarted",
                    Map.of("name", name, "amount", amount, "month", starts.toString()),
                    name + ": your plan of " + amount + "/mo starts " + monthLabel(starts)
                    + " — not counted this month. Edit the loan to start it sooner."));
        }
        return upcoming;
    }

    private static boolean isUzs(Currency c) {
        return c == null || c == Currency.UZS;
    }

    /**
     * Whether a loan/debt's monthly contribution counts toward the tier for the viewed
     * month. True when its payment-start month is on or before {@code month}. Legacy rows
     * (null start) always count, preserving prior behaviour.
     */
    static boolean hasStartedBy(LocalDate paymentStartDate, YearMonth month) {
        if (paymentStartDate == null) return true;
        return !YearMonth.from(paymentStartDate).isAfter(month);
    }

    /** The base level (1–4) for what is left after bills — never above it: Level 5 is earned by pay. */
    private Integer computeLevel(BigDecimal leftMoneyUzs) {
        return baseLevelOf(leftMoneyUzs);
    }

    /**
     * Debt-based sub-level for ANY level: {level}.1 no debt, .2 manageable (ratio ≤ 70%),
     * .3 heavy (&gt; 70%). Reported beside the situation (which splits .2 further); the
     * percentages come from the situation and the level's rules version.
     */
    private String computeSubLevel(Integer level, BigDecimal debtTotalUzs, BigDecimal debtRatio) {
        if (level == null) return null;
        String suffix;
        if (debtTotalUzs.signum() == 0) suffix = "1";
        // Strict: debt payments > 70% of income → heavy (.3); exactly 70% stays manageable (.2).
        else if (debtRatio != null && debtRatio.compareTo(DEBT_RATIO_THRESHOLD) <= 0) suffix = "2";
        else suffix = "3";
        return level + "." + suffix;
    }

    /** Human label for a debt sub-level suffix (".1/.2/.3"). */
    private String subLevelDebtLabel(String subLevel) {
        if (subLevel == null) return "";
        if (subLevel.endsWith(".1")) return "no debt";
        if (subLevel.endsWith(".2")) return "manageable debt (≤ 70% of income)";
        if (subLevel.endsWith(".3")) return "heavy debt (> 70% of income)";
        return "";
    }

    private String computeLevelLabel(Integer level, String subLevel, boolean missingIncome) {
        if (missingIncome) return "Set monthly income to compute tier";
        if (level == null) return "Set monthly income to compute tier";
        if (subLevel != null) return "Level " + subLevel;
        return "Level " + level;
    }

    private static BigDecimal nullToZero(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    // ── Per-bucket paid-this-month + history ──────────────────────────────────

    /**
     * Holds paid-this-month totals in the display currency for each bucket. Computed
     * once per tier request and threaded through into the allocation lines.
     */
    record BucketPaid(BigDecimal donation, BigDecimal emergency, BigDecimal investments, BigDecimal stocks,
                      BigDecimal savings) {}

    /**
     * Paid-this-month sums for the debt actions, in display currency. Drives the "Paid X of Y"
     * progress strip under each action and whether it still locks the buckets.
     *
     * @param ruleRepayments repayments and marks toward the ASAP loans and debts still owed, each up to
     *        its ask, plus repayment transactions not linked to any loan — what the ASAP pay-back asks for
     * @param planRepayments repayments toward loans ON a repayment plan — what the set-aside asks for
     */
    record MonthPaid(BigDecimal bankInstallments, BigDecimal ruleRepayments, BigDecimal planRepayments) {}

    /** Package-private so {@link DailyAdviceService} counts this month's installments the Plan's way. */
    MonthPaid computeMonthPaid(YearMonth month, Currency displayCurrency) {
        return computeMonthPaid(month, displayCurrency, month.atEndOfMonth());
    }

    /**
     * The same, counting only repayments dated on or before {@code asOf} (see
     * {@link #pendingSubscriptions(YearMonth, LocalDate)}); the month's marks always count.
     */
    MonthPaid computeMonthPaid(YearMonth month, Currency displayCurrency, LocalDate asOf) {
        LocalDate start = month.atDay(1);
        LocalDate end = asOf.isBefore(month.atEndOfMonth()) ? asOf : month.atEndOfMonth();
        // The set-aside and the ASAP pay-back are two separate asks, so a repayment must count toward
        // the one it pays. Both used to read ALL of the month's repayments, which let paying only the
        // larger ask satisfy the smaller one too and unlock the buckets while money was still owed.
        // Split by the loan's plan as it stands now — the same test the two targets are built with.
        java.util.Set<Long> plannedLoanIds = new java.util.HashSet<>();
        for (LoanTaken l : loanTakenRepository.findAll()) {
            if (l.effectiveRepaymentType() == RepaymentType.MONTHLY) plannedLoanIds.add(l.getId());
        }
        BigDecimal bank = BigDecimal.ZERO;
        BigDecimal repaidToPlans = BigDecimal.ZERO;
        for (Currency c : Currency.reporting()) {
            BigDecimal bankSum = transactionRepository.sumBySubTypeCurrencyDateRange(
                    uz.tracker.trackerproject.enums.TransactionSubType.BANK_LOAN_PAYMENT, c, start, end);
            if (bankSum != null && bankSum.signum() > 0) {
                bank = bank.add(bankSum);
            }
            if (!plannedLoanIds.isEmpty()) {
                BigDecimal planSum = transactionRepository.sumRepaymentsToLoansTaken(plannedLoanIds, c, start, end);
                if (planSum != null && planSum.signum() > 0) {
                    repaidToPlans = repaidToPlans.add(planSum);
                }
            }
        }
        BigDecimal plan = repaidToPlans;
        // "Already paid" marks (no transaction) for bank installments and MONTHLY loans.
        for (MarkPaid m : markPaidRepository.findByMonth(start)) {
            switch (m.getKind() == null ? "" : m.getKind()) {
                case "BANK" -> bank = bank.add(m.getAmount());
                case "PERSONAL_LOAN" -> {
                    if (m.getRefId() != null && plannedLoanIds.contains(m.getRefId())) plan = plan.add(m.getAmount());
                }
                default -> { }
            }
        }
        // The ASAP pay-back: what went toward each ASAP loan and debt it asks for — its linked
        // repayments and marks, counted up to its own ask — plus repayments naming no loan. Money paid
        // to a MONTHLY loan, or to one already cleared this month, is not part of it: counting that
        // made the pay-back — the bot's "pay debts" sum — smaller than what the ASAP asks still owe.
        BigDecimal rule = nullToZero(unlinkedRepayments(month, end));
        for (DebtAsk a : debtAsks(month, end)) {
            if (a.asap()) rule = rule.add(a.repaid().min(a.ask()));
        }
        return new MonthPaid(bank, rule, plan);
    }

    // ── "Already paid" marks ──────────────────────────────────────────────────

    /**
     * The "already paid" BUCKET marks for {@code month}, on their own — money the user declared
     * settled without recording a transaction. Every screen that quotes a bucket total needs
     * this same sum, either folded in (the plan's figure) or named beside the recorded figure
     * (the month view), so it lives in one place rather than being re-derived per caller.
     *
     * <p>Savings is always zero: SAVINGS is not a markable bucket (see FinanceService.MARK_BUCKETS).
     */
    BucketPaid computeBucketMarks(YearMonth month) {
        BigDecimal donation = BigDecimal.ZERO, emergency = BigDecimal.ZERO;
        BigDecimal investments = BigDecimal.ZERO, stocks = BigDecimal.ZERO;
        for (MarkPaid m : markPaidRepository.findByMonth(month.atDay(1))) {
            if (!"BUCKET".equals(m.getKind()) || m.getBucket() == null) continue;
            BigDecimal amt = m.getAmount();
            switch (m.getBucket()) {
                case "DONATION" -> donation = donation.add(amt);
                case "EMERGENCY" -> emergency = emergency.add(amt);
                case "INVESTMENTS" -> investments = investments.add(amt);
                case "STOCKS" -> stocks = stocks.add(amt);
                default -> { }
            }
        }
        return new BucketPaid(donation, emergency, investments, stocks, BigDecimal.ZERO);
    }

    /** Σ of every BUCKET mark for a month — the part of a bucket total that moved no money. */
    static BigDecimal markedTotal(BucketPaid marks) {
        return marks.donation().add(marks.emergency()).add(marks.investments()).add(marks.stocks());
    }

    /**
     * Fold "already paid" BUCKET marks into a recorded-payments BucketPaid. Package-private and
     * static because {@link MonthCloseService} folds the same marks onto a CLOSED month's frozen
     * snapshot columns — the fold has to be one piece of code, or the plan and the month view
     * drift apart again the moment they each write their own addition.
     */
    static BucketPaid withBucketMarks(BucketPaid base, BucketPaid marks) {
        return new BucketPaid(
                base.donation().add(marks.donation()),
                base.emergency().add(marks.emergency()),
                base.investments().add(marks.investments()),
                base.stocks().add(marks.stocks()),
                base.savings());
    }

    /** One INVESTMENT transaction rendered as a bucket-history row. */
    private uz.tracker.trackerproject.dto.response.BucketPayment investmentBucketRow(
            Transaction t, String bucket) {
        return uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                .id(t.getId())
                .bucket(bucket)
                .date(t.getTransactionDate())
                .amount(t.getAmount())
                .nativeAmount(t.getAmount())
                .nativeCurrency(t.getCurrency())
                .label(t.getDescription())
                .description(t.getNote())
                .build();
    }

    /**
     * Does this INVESTMENT transaction fund a savings goal (rather than a plain investment)?
     *
     * <p>The answer is whatever was RECORDED on the row when the money moved. It used to be
     * re-derived here from the holding's current savingsGoal flag, which stays editable for the
     * whole life of a holding — so ticking that one checkbox emptied the Investments bucket of a
     * month that was already closed, on Plan, on the Investments tab and on Home, while Months
     * went on quoting the frozen month-close snapshot. Ticking "emergency fund" did the same in
     * reverse (it clears savingsGoal). See {@link AllocationBucket}.
     */
    boolean isSavingsGoalTx(Transaction t) {
        if (t.getAllocationBucket() != null) {
            return AllocationBucket.SAVINGS.equals(t.getAllocationBucket());
        }
        // Rows written before the column existed: `ddl-auto=update` adds a column but never fills
        // it, so DataSeeder back-fills the history on boot and this derivation stays as the
        // permanent safety net for anything that escapes it — never the normal path.
        // A contribution row carries investmentId; the row that CREATED the holding is instead
        // referenced by the investment's originatingTransactionId. Unlinked rows (a bare
        // INVESTMENT transaction whose auto-created record is gone) count as a plain investment.
        Investment target = null;
        if (t.getInvestmentId() != null) {
            target = investmentRepository.findById(t.getInvestmentId()).orElse(null);
        }
        if (target == null) {
            target = investmentRepository.findByOriginatingTransactionId(t.getId()).orElse(null);
        }
        return target != null && Boolean.TRUE.equals(target.getSavingsGoal());
    }

    /**
     * The allocation bucket a bucket-funding row credits: the one recorded on it when it was written
     * ({@code Transaction.allocationBucket}), else — a row from before that column — the read-time
     * derivation ({@link AllocationBucket#forSubType} with {@link #isSavingsGoalTx}). Null for a row
     * that funds no bucket. Package-private: Analytics splits "saved" by the same rule.
     */
    String bucketOf(Transaction t) {
        if (t.getAllocationBucket() != null) return t.getAllocationBucket();
        if (t.getSubType() != uz.tracker.trackerproject.enums.TransactionSubType.INVESTMENT) {
            return AllocationBucket.forSubType(t.getSubType(), false);
        }
        return AllocationBucket.forSubType(t.getSubType(), isSavingsGoalTx(t));
    }

    // ── Allocation preview (what would this draft transaction do?) ────────────

    /**
     * Which bucket, if any, a draft transaction would fund. It goes through the two helpers the
     * write path itself uses — {@link AllocationBucket#forHolding} to decide the sub-type a top-up
     * is booked as, then {@link AllocationBucket#forSubType} for the bucket that sub-type credits —
     * so the preview can never promise a bucket the accounting wouldn't actually credit.
     */
    private String bucketForSubType(uz.tracker.trackerproject.enums.TransactionSubType subType, Long investmentId) {
        if (subType == null) return null;
        // Recorded on the row as STOCKS, but stocks are no longer an allocation bucket.
        if (subType == uz.tracker.trackerproject.enums.TransactionSubType.STOCK_PURCHASE) return null;
        Investment holding = null;
        if (investmentId != null && (subType == uz.tracker.trackerproject.enums.TransactionSubType.INVESTMENT
                || subType == uz.tracker.trackerproject.enums.TransactionSubType.EMERGENCY_CONTRIBUTION)) {
            holding = investmentRepository.findById(investmentId).orElse(null);
        }
        var booked = AllocationBucket.forHolding(subType, holding);
        return AllocationBucket.forSubType(booked,
                holding != null && Boolean.TRUE.equals(holding.getSavingsGoal()));
    }

    @Transactional(readOnly = true)
    public uz.tracker.trackerproject.dto.response.AllocationPreviewResponse previewAllocation(
            uz.tracker.trackerproject.dto.request.AllocationPreviewRequest req, Currency display) {

        String bucket = bucketForSubType(req.getSubType(), req.getInvestmentId());
        if (bucket == null) {
            return uz.tracker.trackerproject.dto.response.AllocationPreviewResponse.builder()
                    .applicable(false)
                    .message("This transaction doesn't fund any allocation bucket.")
                    .build();
        }

        YearMonth month = YearMonth.from(req.getTransactionDate());
        OverviewTierResponse tier = getTier(month, display);
        BigDecimal amount = req.getAmount() == null ? BigDecimal.ZERO : req.getAmount();

        TierAllocation.AllocationLine line = tier.getAllocation() == null ? null
                : tier.getAllocation().getLines().stream()
                        .filter(l -> bucket.equals(l.getBucket()))
                        .findFirst().orElse(null);

        String label = line != null ? line.getLabel() : bucket;

        // No line, or a 0% bucket at this tier: the money still lands there, it just isn't
        // being asked of the user this month. Say so rather than showing a target of zero.
        if (line == null || !line.isRecommended()) {
            return uz.tracker.trackerproject.dto.response.AllocationPreviewResponse.builder()
                    .applicable(true)
                    .bucket(bucket)
                    .label(label)
                    .bucketNotRecommended(true)
                    .amount(amount)
                    .paidBefore(line == null ? BigDecimal.ZERO : line.getPaidAmount())
                    .paidAfter((line == null ? BigDecimal.ZERO : line.getPaidAmount()).add(amount))
                    .message("Counts toward " + label + ", which isn't required at your current tier"
                            + " — it's recorded, but nothing is expected this month.")
                    .build();
        }

        BigDecimal recommended = nullToZero(line.getMinAmount());
        BigDecimal paidBefore = nullToZero(line.getPaidAmount());
        BigDecimal paidAfter = paidBefore.add(amount);
        BigDecimal remainingBefore = clampZero(recommended.subtract(paidBefore));
        BigDecimal remainingAfter = clampZero(recommended.subtract(paidAfter));
        boolean completes = remainingBefore.signum() > 0 && remainingAfter.signum() == 0;

        String msg = completes
                ? "This covers the rest of your " + label + " for " + monthLabel(month) + "."
                : remainingAfter.signum() == 0
                    ? label + " was already covered for " + monthLabel(month) + "."
                    : "Leaves " + remainingAfter + " still to put aside for " + label + ".";

        return uz.tracker.trackerproject.dto.response.AllocationPreviewResponse.builder()
                .applicable(true)
                .bucket(bucket)
                .label(label)
                .recommended(recommended)
                .paidBefore(paidBefore)
                .amount(amount)
                .paidAfter(paidAfter)
                .remainingBefore(remainingBefore)
                .remainingAfter(remainingAfter)
                .completesBucket(completes)
                .message(msg)
                .build();
    }

    /**
     * Paid-this-month per bucket, marks included. This is the figure every screen quotes, so it
     * is the default; the ONE caller that must not see marks (the irreversible month-close
     * snapshot) opts out explicitly via the three-argument form below.
     */
    BucketPaid computePaidThisMonth(YearMonth month, Currency displayCurrency) {
        return computePaidThisMonth(month, displayCurrency, true);
    }

    /**
     * @param includeMarks fold this month's "already paid" BUCKET marks in. True for anything the
     *        user reads as "paid" (tier, ledger, month summary, close preview). False only for the
     *        month-close snapshot: a mark moves no tracked money, so counting it there would
     *        overstate taggedTotal and silently shrink {@code everydaySpend = totalSpent − tagged}.
     */
    BucketPaid computePaidThisMonth(YearMonth month, Currency displayCurrency, boolean includeMarks) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        BigDecimal donation = BigDecimal.ZERO;
        for (Donation d : donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(start, end)) {
            donation = donation.add(d.getAmount());
        }

        // Emergency bucket = EMERGENCY_CONTRIBUTION transactions (the actual money out — covers both
        // the Emergencies CRUD, which mirrors a tx, AND a generic-modal EMERGENCY_CONTRIBUTION row)
        // plus emergency-fund-flagged investments below. Counting the tx (not the Emergency entity)
        // means a contribution recorded via the transaction modal is no longer missed.
        BigDecimal emergency = BigDecimal.ZERO;
        for (Currency c : Currency.reporting()) {
            BigDecimal sum = transactionRepository.sumBySubTypeCurrencyDateRange(
                    uz.tracker.trackerproject.enums.TransactionSubType.EMERGENCY_CONTRIBUTION, c, start, end);
            if (sum != null && sum.signum() > 0) emergency = emergency.add(sum);
        }

        // Investments + Savings are counted from INVESTMENT transactions BY TRANSACTION DATE, the
        // same way Emergency and Stocks are. Counting the Investment entity by purchaseDate instead
        // (the old behaviour) credited every later top-up to the month the holding was first bought
        // — so money spent this month was invisible, a past (possibly closed) month silently grew,
        // and a top-up aimed at a savings goal or emergency fund landed in no bucket at all.
        // Money that never moved (opening balances, "None"/noWallet contributions) books no
        // transaction, so it is now excluded automatically rather than by an explicit flag check.
        BigDecimal investments = BigDecimal.ZERO;
        BigDecimal savings = BigDecimal.ZERO;
        for (Transaction t : transactionRepository
                .findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                        uz.tracker.trackerproject.enums.TransactionSubType.INVESTMENT, start, end)) {
            BigDecimal amt = t.getAmount();
            if (amt == null || amt.signum() <= 0) continue;
            if (isSavingsGoalTx(t)) savings = savings.add(amt);
            else investments = investments.add(amt);
        }

        // Stocks are tracked in a separate app — the bucket is funded by STOCK_PURCHASE transactions.
        BigDecimal stocks = BigDecimal.ZERO;
        for (Currency c : Currency.reporting()) {
            BigDecimal sum = transactionRepository.sumBySubTypeCurrencyDateRange(
                    uz.tracker.trackerproject.enums.TransactionSubType.STOCK_PURCHASE, c, start, end);
            if (sum != null && sum.signum() > 0) stocks = stocks.add(sum);
        }

        BucketPaid recorded = new BucketPaid(donation, emergency, investments, stocks, savings);
        return includeMarks ? withBucketMarks(recorded, computeBucketMarks(month)) : recorded;
    }

    /**
     * Per-bucket payment history for a month. Returns transactions in the entity's
     * "natural" date field (donationDate / date / purchaseDate), normalised into a
     * common BucketPayment row shape so the frontend can render uniformly, plus the
     * month's "already paid" marks — the rows must add up to the bucket total shown
     * above them, and the plan's total counts marks.
     */
    @Transactional(readOnly = true)
    public List<uz.tracker.trackerproject.dto.response.BucketPayment> getBucketPayments(
            String bucket, YearMonth month, Currency displayCurrency) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<uz.tracker.trackerproject.dto.response.BucketPayment> rows = new ArrayList<>();
        String key = bucket.toUpperCase();

        switch (key) {
            case "DONATION" -> donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(start, end)
                    .forEach(d -> rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                            .id(d.getId())
                            .bucket("DONATION")
                            .date(d.getDonationDate())
                            .amount(d.getAmount())
                            .nativeAmount(d.getAmount())
                            .nativeCurrency(d.getCurrency())
                            .label(Boolean.TRUE.equals(d.getAnonymous()) ? "Anonymous" : d.getRecipientName())
                            .description(d.getDescription())
                            .build()));
            case "EMERGENCY" -> {
                transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                                uz.tracker.trackerproject.enums.TransactionSubType.EMERGENCY_CONTRIBUTION, start, end)
                        .forEach(t -> rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                                .id(t.getId())
                                .bucket("EMERGENCY")
                                .date(t.getTransactionDate())
                                .amount(t.getAmount())
                                .nativeAmount(t.getAmount())
                                .nativeCurrency(t.getCurrency())
                                .label("Emergency fund")
                                .description(t.getDescription())
                                .build()));
                // Emergency-fund investment funding (initial + top-ups) books EMERGENCY_CONTRIBUTION
                // transactions, already listed above — no separate by-entity listing needed.
            }
            // Both read the SAME source as computePaidThisMonth (INVESTMENT transactions by
            // transaction date, split by the bucket each row recorded when it was written) so the
            // history rows always add up to the bucket total shown above them.
            case "INVESTMENTS" -> transactionRepository
                    .findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                            uz.tracker.trackerproject.enums.TransactionSubType.INVESTMENT, start, end)
                    .stream().filter(t -> !isSavingsGoalTx(t))
                    .forEach(t -> rows.add(investmentBucketRow(t, "INVESTMENTS")));
            case "SAVINGS" -> transactionRepository
                    .findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                            uz.tracker.trackerproject.enums.TransactionSubType.INVESTMENT, start, end)
                    .stream().filter(this::isSavingsGoalTx)
                    .forEach(t -> rows.add(investmentBucketRow(t, "SAVINGS")));
            case "STOCKS" -> transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                            uz.tracker.trackerproject.enums.TransactionSubType.STOCK_PURCHASE, start, end)
                    .forEach(t -> rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                            .id(t.getId())
                            .bucket("STOCKS")
                            .date(t.getTransactionDate())
                            .amount(t.getAmount())
                            .nativeAmount(t.getAmount())
                            .nativeCurrency(t.getCurrency())
                            .label("Stocks")
                            .description(t.getDescription())
                            .build()));
            default -> throw new IllegalArgumentException("Unknown bucket: " + bucket);
        }

        // The marks the tier folds into "Paid" belong here too: without them the panel's rows can
        // never reach the figure on the card above, which is the whole reason the user opened it.
        // They carry a MarkPaid id and moved no money, so `marked` tells the client not to treat
        // the row as an editable Donation / Transaction.
        for (MarkPaid m : markPaidRepository.findByMonth(start)) {
            if (!"BUCKET".equals(m.getKind()) || !key.equals(m.getBucket())) continue;
            rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                    .id(m.getId())
                    .bucket(key)
                    .date(m.getMonth())
                    .amount(m.getAmount())
                    .nativeAmount(m.getAmount())
                    .nativeCurrency(m.getCurrency())
                    .label("Marked as already paid")
                    .description(m.getNote())
                    .marked(true)
                    .build());
        }
        rows.sort(Comparator.comparing(
                uz.tracker.trackerproject.dto.response.BucketPayment::getDate,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    // ── Allocation guidance ───────────────────────────────────────────────────

    /**
     * The allocation for a month, at every level the same way (LEVELS-ALLOCATION-SPEC §1.2): the
     * situation is picked as Level 1's scenario always was — from the stable income, the month's bank
     * loans and debt asks (the 10% small-monthly-loan rule applied) and the split line of the level's
     * version in force — and the version's numbers ask. The base the percentages multiply is
     * {@code allocBaseUzs}, the stable income + this month's bonus; the bonus takes no part in
     * picking the situation.
     */
    private TierAllocation computeAllocation(
            YearMonth month, SavingsRules.Book book, Integer level,
            BigDecimal incomeUzs, BigDecimal allocBaseUzs, BigDecimal mandatoryUzs,
            BigDecimal bankMonthlyUzs,
            BigDecimal debt34Uzs, BigDecimal debtRatio,
            BigDecimal plannedSetAsideUzs,
            Currency displayCurrency, BucketPaid paid, BucketPaid marks, MonthPaid monthPaid,
            List<ActionItem> upcoming) {

        if (level == null) {
            return notDefinedAllocation("page.plan.note.setIncome", Map.of(),
                    "Set monthly income to see allocation guidance.");
        }
        SavingsRules.Version version = book.version(level, month);
        // loanInstallments = bank only → ZERO in the 4th slot, since borrowed money is personal debt.
        SavingsRules.Pick pick = SavingsRules.pick(incomeUzs, mandatoryUzs, bankMonthlyUzs, BigDecimal.ZERO,
                debt34Uzs, ruleDebtUzs(debt34Uzs, plannedSetAsideUzs, incomeUzs), debtRatio, version.cutoff());
        String[] p = version.percents(pick.situation()).strings();
        List<AllocationLine> lines = percentLines(allocBaseUzs, displayCurrency, paid, marks,
                p[0], p[1], p[2]);

        // Bank installments pay via PayBankInstallmentModal; the set-aside and the ASAP pay-back
        // (borrowed money + debts) via PayPersonalLoanModal.
        List<ActionItem> actions = situationActions(pick.situation(), monthPaid, bankMonthlyUzs,
                nullToZero(debt34Uzs), plannedSetAsideUzs, displayCurrency, upcoming);
        String key = SavingsRules.scenarioKey(level, pick.situation());

        return TierAllocation.builder()
                .scenarioKey(key)
                .scenarioLabel(scenarioLabel(level, pick.situation(), version.cutoff()))
                .lines(lines)
                .actions(actions)
                .allocationLocked(isAllocationLocked(actions))
                .build();
    }

    /**
     * Level 1's scenario with Level 1's built-in numbers — the choice every level now makes
     * ({@link SavingsRules#pick}), kept with its original shape: a "1.x" key, the four-wide
     * percentage strings (the retired Stocks slot last) and the calc base. Pure, for the tests that
     * pin the choice itself.
     */
    static Level1Plan computeLevel1Plan(BigDecimal incomeUzs, BigDecimal mandatoryUzs,
            BigDecimal bankMonthlyUzs, BigDecimal loanTakenUzs, BigDecimal debt34Uzs,
            BigDecimal debtRatio, BigDecimal cutoffUzs) {
        return computeLevel1Plan(incomeUzs, mandatoryUzs, bankMonthlyUzs, loanTakenUzs, debt34Uzs, debt34Uzs,
                debtRatio, cutoffUzs);
    }

    /**
     * The same, telling apart the debt that is PAID ({@code debt34Uzs}: every ask of the month — what
     * the tight/comfortable calc base subtracts, and what the heavy-debt ratio is built from) from the
     * debt that COUNTS for the choice of the rule ({@code ruleDebtUzs}, see {@link #ruleDebtUzs}).
     */
    static Level1Plan computeLevel1Plan(BigDecimal incomeUzs, BigDecimal mandatoryUzs,
            BigDecimal bankMonthlyUzs, BigDecimal loanTakenUzs, BigDecimal debt34Uzs, BigDecimal ruleDebtUzs,
            BigDecimal debtRatio, BigDecimal cutoffUzs) {
        SavingsRules.Pick pick = SavingsRules.pick(incomeUzs, mandatoryUzs, bankMonthlyUzs, loanTakenUzs,
                debt34Uzs, ruleDebtUzs, debtRatio, cutoffUzs);
        String[] pct = switch (pick.situation()) {
            case SavingsRules.NO_DEBT -> new String[]{"10", "5", "15", "5"};
            case SavingsRules.HEAVY_DEBT -> new String[]{"2", null, null, null};
            case SavingsRules.BANK_AND_DEBTS -> new String[]{"5", null, "5", null};
            case SavingsRules.BANK_LOAN_TIGHT, SavingsRules.DEBTS_TIGHT -> new String[]{"5", "2", "8", null};
            default -> new String[]{"7", "3", "10", "3"};
        };
        return new Level1Plan(SavingsRules.scenarioKey(1, pick.situation()), pct, pick.calcBase(),
                pick.hasLoan(), pick.hasDebt());
    }

    /** A MONTHLY loan's plans count for the savings rule only above this share of the stable income. */
    private static final BigDecimal SMALL_MONTHLY_SHARE = new BigDecimal("0.10");

    /** 10% of the stable income: MONTHLY plans up to it (inclusive) leave the savings rule alone. */
    static BigDecimal monthlyLoanLimitUzs(BigDecimal stableUzs) {
        return nullToZero(stableUzs).multiply(SMALL_MONTHLY_SHARE);
    }

    /**
     * The personal debt that counts when the savings rule is chosen (the owner's rule, 2026-09-30):
     * every ASAP ask, plus the MONTHLY plans only when together they are MORE than 10% of the stable
     * income (exactly 10% does not count). The payments themselves — the asks, the debt ratio behind
     * "heavy", the calc base behind tight/comfortable — still use every ask.
     *
     * @param allAsksUzs     the month's asks on borrowed money and debts, MONTHLY and ASAP
     * @param monthlyAsksUzs the MONTHLY plans among them
     */
    static BigDecimal ruleDebtUzs(BigDecimal allAsksUzs, BigDecimal monthlyAsksUzs, BigDecimal stableUzs) {
        BigDecimal monthly = nullToZero(monthlyAsksUzs);
        BigDecimal asap = clampZero(nullToZero(allAsksUzs).subtract(monthly));
        return monthly.compareTo(monthlyLoanLimitUzs(stableUzs)) > 0 ? asap.add(monthly) : asap;
    }

    /** Result of the Level-1 engine: scenario key, bucket percentages, and the calc base (UZS). */
    record Level1Plan(String scenarioKey, String[] pct, BigDecimal calcBaseUzs,
                      boolean hasLoan, boolean hasDebt) {}

    private static BigDecimal clampZero(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    /** The action items of a situation, at any level (keeps PAY_BANK / PAY_PERSONAL_LOAN keys for the UI). */
    private List<ActionItem> situationActions(String situation, MonthPaid monthPaid,
            BigDecimal bankMonthlyUzs, BigDecimal debt34Uzs,
            BigDecimal plannedSetAsideUzs, Currency cur, List<ActionItem> upcoming) {
        // NO_DEBT is the situation without debt that counts for the rule — but a small MONTHLY plan is
        // still to be paid (its set-aside), and a repayment that merely has not STARTED yet is still
        // worth showing, or the money looks as if it vanished.
        if (SavingsRules.NO_DEBT.equals(situation)) {
            List<ActionItem> only = new ArrayList<>();
            addDebtActions(only, debt34Uzs, plannedSetAsideUzs, cur, monthPaid);
            only.addAll(upcoming);
            return only;
        }
        List<ActionItem> actions = new ArrayList<>();
        if (bankMonthlyUzs.signum() > 0) {
            actions.add(payBank(monthPaid, bankMonthlyUzs));
        }
        addDebtActions(actions, debt34Uzs, plannedSetAsideUzs, cur, monthPaid);
        switch (situation) {
            case SavingsRules.BANK_LOAN_TIGHT, SavingsRules.DEBTS_TIGHT ->
                    actions.add(info("page.plan.note.tight", Map.of(),
                            "Less than 5M UZS remains after debt — slim allocations until things ease up."));
            case SavingsRules.BANK_LOAN_COMFORTABLE, SavingsRules.DEBTS_COMFORTABLE ->
                    actions.add(info("page.plan.note.comfortable", Map.of(),
                            "5M+ UZS remains after debt — higher allocations apply."));
            case SavingsRules.BANK_AND_DEBTS ->
                    actions.add(info("page.plan.note.loanAndDebt", Map.of(),
                            "Both loan and debt — emergency is skipped this tier; focus on debt."));
            case SavingsRules.HEAVY_DEBT ->
                    actions.add(info("page.plan.note.heavyDebt", Map.of(),
                            "Heavy debt (> 70% of income): only a 2% donation this month. "
                            + "You may withdraw from the emergency fund if the situation gets really bad."));
            default -> { }
        }
        actions.addAll(upcoming);
        return actions;
    }

    /** "Level 1.2 — bank loan only, tight (< 5M UZS after debt)", the same words at every level. */
    private static String scenarioLabel(int level, String situation, BigDecimal cutoff) {
        String split = cutoff == null || cutoff.compareTo(SavingsRules.DEFAULT_CUTOFF) == 0 ? "5M"
                : cutoff.stripTrailingZeros().toPlainString();
        return switch (situation) {
            case SavingsRules.NO_DEBT -> "Level " + level + ".1 — no debts";
            case SavingsRules.BANK_LOAN_TIGHT -> "Level " + level + ".2 — bank loan only, tight (< " + split + " UZS after debt)";
            case SavingsRules.BANK_LOAN_COMFORTABLE -> "Level " + level + ".2 — bank loan only, comfortable (≥ " + split + " UZS after debt)";
            case SavingsRules.DEBTS_TIGHT -> "Level " + level + ".2 — debts only, tight (< " + split + " UZS after debt)";
            case SavingsRules.DEBTS_COMFORTABLE -> "Level " + level + ".2 — debts only, comfortable (≥ " + split + " UZS after debt)";
            case SavingsRules.BANK_AND_DEBTS -> "Level " + level + ".2 — bank loan + debts (fixed allocation)";
            case SavingsRules.HEAVY_DEBT -> "Level " + level + ".3 — heavy debt (> 70% of income)";
            default -> "Guidance not yet defined for this tier";
        };
    }

    // ── The levels' savings rules, version by version ─────────────────────────

    /**
     * Every level's rules versions (LEVELS-ALLOCATION-SPEC §1.2–1.3), read once per call: the stored
     * versions, and for a level with none — a service built by hand, or a database before the boot
     * seeding — the first version the seeding writes ({@link #seedVersion}).
     */
    SavingsRules.Book ruleBook() {
        java.util.Map<Integer, java.util.NavigableMap<YearMonth, SavingsRules.Version>> levels = new java.util.HashMap<>();
        if (levelRuleVersionRepository != null) {
            List<uz.tracker.trackerproject.entity.LevelRuleVersion> stored =
                    levelRuleVersionRepository.findAllByOrderByLevelAscFromMonthAsc();
            for (uz.tracker.trackerproject.entity.LevelRuleVersion v : stored == null
                    ? List.<uz.tracker.trackerproject.entity.LevelRuleVersion>of() : stored) {
                SavingsRules.Version version = SavingsRules.Version.of(v);
                levels.computeIfAbsent(version.level(), l -> new java.util.TreeMap<>()).put(version.from(), version);
            }
        }
        YearMonth first = null;
        for (int level = 1; level <= SavingsRules.TOP_LEVEL; level++) {
            if (levels.containsKey(level)) continue;
            if (first == null) first = firstLevelMonth(YearMonth.now());
            levels.put(level, SavingsRules.single(seedVersion(level, first)));
        }
        return SavingsRules.book(levels);
    }

    /**
     * A level's first version, as the boot seeding stores it — so that nothing past moves: Level 1
     * takes today's built-in table and its split line ({@code LevelConfig(1).minLeftover ?? 5M});
     * Levels 2–5 take the rules already saved for them in the old store, situation by situation
     * ({@code L.1} → no loans; {@code L.2} → the four split rows and bank loan + loans from people;
     * {@code L.3} → heavy loans), and Level 1's numbers wherever none was saved. Every level starts at
     * Level 1's split line (the owner's answer, §6.1).
     */
    SavingsRules.Version seedVersion(int level, YearMonth from) {
        BigDecimal cutoff = minLeftoverUzs(1);
        java.util.Map<String, SavingsRules.Percents> rules = new java.util.LinkedHashMap<>(SavingsRules.LEVEL1_DEFAULT);
        if (level >= 2) {
            seedFromOldRule(rules, level + ".1", List.of(SavingsRules.NO_DEBT));
            seedFromOldRule(rules, level + ".2", List.of(SavingsRules.BANK_LOAN_COMFORTABLE, SavingsRules.BANK_LOAN_TIGHT,
                    SavingsRules.DEBTS_COMFORTABLE, SavingsRules.DEBTS_TIGHT, SavingsRules.BANK_AND_DEBTS));
            seedFromOldRule(rules, level + ".3", List.of(SavingsRules.HEAVY_DEBT));
        }
        return new SavingsRules.Version(level, from, cutoff, rules);
    }

    private void seedFromOldRule(java.util.Map<String, SavingsRules.Percents> rules, String subLevel, List<String> situations) {
        LevelAllocationRule old = ruleRepository.findBySubLevel(subLevel).orElse(null);
        if (old == null) return;
        SavingsRules.Percents p = new SavingsRules.Percents(nullToZero(old.getDonationPercent()),
                nullToZero(old.getEmergencyPercent()), nullToZero(old.getInvestmentsPercent()));
        for (String s : situations) rules.put(s, p);
    }

    // ── Which level a month is on (LEVELS-ALLOCATION-SPEC §1.1) ───────────────

    /** The recorded starts and ends of Level 5, oldest first (none where the service is built by hand). */
    List<uz.tracker.trackerproject.entity.LevelChange> levelChanges() {
        if (levelChangeRepository == null) return List.of();
        List<uz.tracker.trackerproject.entity.LevelChange> rows = levelChangeRepository.findAllByOrderByMonthAscIdAsc();
        return rows == null ? List.of() : rows;
    }

    /** Levels 1–4 from what is left after bills: 1 under 15M · 2 under 30M · 3 under 45M · 4 from 45M. */
    static int baseLevelOf(BigDecimal leftAfterBillsUzs) {
        BigDecimal left = nullToZero(leftAfterBillsUzs);
        for (int i = 0; i < LEVEL_BREAKPOINTS_UZS.length; i++) {
            if (left.compareTo(LEVEL_BREAKPOINTS_UZS[i]) < 0) return i + 1;
        }
        return TOP_BASE_LEVEL;
    }

    /** {@code month}'s level: 5 while a recorded Level 5 period covers it, else its base level. */
    int levelOf(YearMonth month, BigDecimal leftAfterBillsUzs, List<uz.tracker.trackerproject.entity.LevelChange> changes) {
        return level5Since(month, changes) != null ? SavingsRules.TOP_LEVEL : baseLevelOf(leftAfterBillsUzs);
    }

    /** The month the Level 5 period covering {@code month} started; null when none covers it. */
    static YearMonth level5Since(YearMonth month, List<uz.tracker.trackerproject.entity.LevelChange> changes) {
        uz.tracker.trackerproject.entity.LevelChange latest = null;
        for (uz.tracker.trackerproject.entity.LevelChange c : changes) {
            if (c.getMonth() == null || YearMonth.from(c.getMonth()).isAfter(month)) continue;
            latest = c;
        }
        return latest != null && uz.tracker.trackerproject.entity.LevelChange.UP.equals(latest.getKind())
                ? YearMonth.from(latest.getMonth()) : null;
    }

    /** {@code month}'s level as the engine evaluates it; null without an income that month. */
    Integer levelFor(YearMonth month) {
        BigDecimal income = stableIncomeFor(month);
        if (income == null || income.signum() <= 0) return null;
        return levelOf(month, income.subtract(sumActiveSubscriptionsUzs()), levelChanges());
    }

    /** Today's active monthly bills, UZS — what every month's level is measured after. */
    BigDecimal activeBillsUzs() {
        return sumActiveSubscriptionsUzs();
    }

    /** {@code month}'s base level (1–4) from its income − today's bills; null without an income. */
    Integer baseLevelFor(YearMonth month) {
        BigDecimal income = stableIncomeFor(month);
        if (income == null || income.signum() <= 0) return null;
        return baseLevelOf(income.subtract(sumActiveSubscriptionsUzs()));
    }

    /**
     * Pay for {@code month} (Profile's "Pay for {month}"): the salary tree's income, bonus included,
     * counted in its accounting month, UZS, rows dated up to {@code asOf}.
     */
    BigDecimal payFor(YearMonth month, LocalDate asOf) {
        return nullToZero(salaryReceivedUzs(month, asOf)).add(nullToZero(sumBonusIncomeUzs(month)));
    }

    /**
     * The first month the levels count from, and the earliest a rules version may start: the tracking
     * start, else the month of the earliest transaction, else {@code current}.
     */
    YearMonth firstLevelMonth(YearMonth current) {
        Settings s = settingsService.getOrCreate();
        if (s != null && s.getAllocationTrackingStartMonth() != null) return YearMonth.from(s.getAllocationTrackingStartMonth());
        LocalDate earliest = transactionRepository.findEarliestTransactionDate();
        return earliest != null ? YearMonth.from(earliest) : current;
    }

    /** One month of a Level 5 run, with its pay. */
    record MonthPay(YearMonth month, BigDecimal pay) {}

    /**
     * Where the Level 5 run stands at {@code current} (LEVELS-ALLOCATION-SPEC §1.1), read only.
     *
     * <p>Walks the ENDED months from the first month: the recorded changes before {@code current} are
     * the truth (once a change's month has ended it stands); a month on Level 4 with pay ≥ 60M adds to
     * the run toward Level 5, a month on Level 5 with pay under it to the run back; any other month
     * resets it. A run of three makes a change from the month after its third — recorded only when
     * that month is {@code current} ({@code pending}); one that would apply to a month already ended
     * is not written in the past, and the run goes on (a late salary then starts Level 5 with the
     * month it was recorded in).
     *
     * @param run     the run now counting, oldest first (empty right after a change)
     * @param pending the change that should be recorded from {@code current}; null when none
     * @param deciding for {@code pending}: the three months that decided it
     */
    record Standing(boolean onLevel5, List<MonthPay> run, String pending, List<MonthPay> deciding) {}

    /**
     * The road to Level 5 at {@code current} (on Level 4), or back from it (on Level 5); null on
     * Levels 1–3 and without an income. {@code appliesFrom}: the month after the run would complete
     * if every month from this one counts.
     */
    uz.tracker.trackerproject.dto.response.LevelRoad road(YearMonth current, LocalDate asOf, Integer level, Integer baseLevel) {
        if (level == null || baseLevel == null) return null;
        boolean onLevel5 = level == SavingsRules.TOP_LEVEL;
        if (!onLevel5 && baseLevel != TOP_BASE_LEVEL) return null;
        Standing st = standing(current, asOf);
        List<uz.tracker.trackerproject.dto.response.LevelRoad.MonthPay> months = new ArrayList<>();
        // The run toward where this month is headed: only meaningful in the state the month is in.
        if (st.onLevel5() == onLevel5) {
            for (MonthPay m : st.run()) {
                months.add(uz.tracker.trackerproject.dto.response.LevelRoad.MonthPay.builder()
                        .month(m.month().toString()).pay(m.pay()).build());
            }
        }
        int still = Math.max(1, LEVEL5_MONTHS_NEEDED - months.size());
        return uz.tracker.trackerproject.dto.response.LevelRoad.builder()
                .toward(onLevel5 ? baseLevel : SavingsRules.TOP_LEVEL)
                .payThreshold(LEVEL5_PAY_THRESHOLD)
                .monthsNeeded(LEVEL5_MONTHS_NEEDED)
                .months(months)
                .thisMonthSoFar(payFor(current, asOf))
                .appliesFrom(current.plusMonths(still).toString())
                .build();
    }

    Standing standing(YearMonth current, LocalDate asOf) {
        List<uz.tracker.trackerproject.entity.LevelChange> changes = levelChanges();
        java.util.Map<YearMonth, String> frozen = new java.util.HashMap<>();
        for (uz.tracker.trackerproject.entity.LevelChange c : changes) {
            YearMonth m = YearMonth.from(c.getMonth());
            if (m.isBefore(current)) frozen.put(m, c.getKind());
        }
        StableIncomeSchedule income = incomeSchedule();
        BigDecimal bills = sumActiveSubscriptionsUzs();
        boolean level5 = false;
        List<MonthPay> run = new ArrayList<>();
        String pending = null;
        List<MonthPay> deciding = List.of();
        for (YearMonth m = firstLevelMonth(current); m.isBefore(current); m = m.plusMonths(1)) {
            String kind = frozen.get(m);
            if (kind != null) {
                level5 = uz.tracker.trackerproject.entity.LevelChange.UP.equals(kind);
                run.clear();
            }
            BigDecimal stable = income.amountFor(m);
            BigDecimal pay = payFor(m, asOf);
            boolean counts;
            if (!level5) {
                counts = stable != null && stable.signum() > 0
                        && baseLevelOf(stable.subtract(bills)) == TOP_BASE_LEVEL
                        && pay.compareTo(LEVEL5_PAY_THRESHOLD) >= 0;
            } else {
                counts = pay.compareTo(LEVEL5_PAY_THRESHOLD) < 0;
            }
            if (counts) run.add(new MonthPay(m, pay)); else run.clear();
            if (run.size() >= LEVEL5_MONTHS_NEEDED && m.plusMonths(1).equals(current)) {
                pending = level5 ? uz.tracker.trackerproject.entity.LevelChange.DOWN : uz.tracker.trackerproject.entity.LevelChange.UP;
                deciding = List.copyOf(run.subList(run.size() - LEVEL5_MONTHS_NEEDED, run.size()));
            }
        }
        if (pending != null) {
            level5 = uz.tracker.trackerproject.entity.LevelChange.UP.equals(pending);
            run.clear();
        }
        return new Standing(level5, List.copyOf(run), pending, deciding);
    }

    private static String toPctStr(BigDecimal pct) {
        return pct == null ? null : pct.stripTrailingZeros().toPlainString();
    }

    // ── The old per-level config ──────────────────────────────────────────────

    /**
     * The old store's minimum leftover for a level, in UZS (default 5M). Since the levels' rules are
     * versions (2026-10-01) it is read only when Level 1's first version is seeded — its split line.
     */
    BigDecimal minLeftoverUzs(int level) {
        BigDecimal v = levelConfigRepository.findByLevel(level)
                .map(LevelConfig::getMinLeftover).orElse(null);
        if (v != null) return v;
        return FIVE_MILLION_UZS;
    }

    /**
     * The left-after-bills band of a level: Level 1 from 0, Level 2 from 15M, Level 3 from 30M,
     * Level 4 from 45M; Level 5 has none (it is earned by pay) — null.
     */
    static BigDecimal levelIncomeLow(int level) {
        if (level >= SavingsRules.TOP_LEVEL) return null;
        return level <= 1 ? BigDecimal.ZERO : LEVEL_BREAKPOINTS_UZS[level - 2];
    }

    /** Where the next band starts: 15M · 30M · 45M; null on Levels 4 and 5 (no upper limit). */
    static BigDecimal levelIncomeHigh(int level) {
        return level >= TOP_BASE_LEVEL ? null : LEVEL_BREAKPOINTS_UZS[level - 1];
    }

    /**
     * The levels' rules as the old Rules view showed them — read-only, mapped from the store
     * (LEVELS-ALLOCATION-SPEC §3.5): Levels 1–5, each with the version in force this month, its
     * sub-levels .1 = no loans, .2 = bank loan with "or more left", .3 = heavy loans. Nothing is
     * locked or editable here any more: rules are changed through {@code PUT /api/v1/levels/{level}/rules}.
     */
    @Transactional(readOnly = true)
    public AllocationRulesViewResponse getAllocationRules() {
        YearMonth now = YearMonth.now();
        BigDecimal currentIncome = stableIncomeFor(now);
        boolean missingIncome = currentIncome == null || currentIncome.signum() <= 0;

        Integer curLevel = missingIncome ? null : levelFor(now);
        String curSubLevel = null;
        if (curLevel != null) {
            BigDecimal stableUzs = currentIncome;
            BigDecimal bankNow = sumBankLoanMonthlyPaymentsUzs(now);
            List<DebtAsk> asksNow = debtAsks(now, now.atEndOfMonth());
            BigDecimal allAsks = asksNow.stream().map(DebtAsk::ask).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal monthlyAsks = asksNow.stream().filter(a -> !a.asap())
                    .map(DebtAsk::ask).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal debtTotal = bankNow.add(allAsks);
            BigDecimal ratio = stableUzs.signum() > 0 ? debtTotal.divide(stableUzs, MC) : null;
            curSubLevel = computeSubLevel(curLevel, bankNow.add(ruleDebtUzs(allAsks, monthlyAsks, stableUzs)), ratio);
        }

        SavingsRules.Book book = ruleBook();
        List<LevelView> levels = new ArrayList<>();
        for (int level = 1; level <= SavingsRules.TOP_LEVEL; level++) {
            SavingsRules.Version v = book.version(level, now);
            List<SubLevelView> subs = new ArrayList<>(3);
            String[] situations = {SavingsRules.NO_DEBT, SavingsRules.BANK_LOAN_COMFORTABLE, SavingsRules.HEAVY_DEBT};
            for (int sub = 1; sub <= 3; sub++) {
                String subLevel = level + "." + sub;
                SavingsRules.Percents p = v.percents(situations[sub - 1]);
                subs.add(SubLevelView.builder()
                        .subLevel(subLevel)
                        .debtLabel(subLevelDebtLabel(subLevel))
                        .donationPercent(notAskedNull(p.donation()))
                        .emergencyPercent(notAskedNull(p.emergency()))
                        .investmentsPercent(notAskedNull(p.investments()))
                        .stocksPercent(null)
                        .build());
            }
            levels.add(LevelView.builder()
                    .level(level)
                    .incomeLow(levelIncomeLow(level))
                    .incomeHigh(levelIncomeHigh(level))
                    .minLeftover(v.cutoff())
                    .expirationMonth(null)
                    .locked(false)
                    .editable(false)
                    .builtIn(level == 1)
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

    /** 0 is "not asked", which this view always sent as null. */
    private static BigDecimal notAskedNull(BigDecimal v) {
        return v == null || v.signum() == 0 ? null : v;
    }

    /**
     * Action item helpers.
     *
     * <p>Every sentence below is composed here, in English, so each carries the translation key
     * and the values to interpolate alongside it: `text` is what an older client prints, `code` +
     * `params` are what a current one renders in the viewer's language. Both are built from the
     * same locals, so the two forms cannot drift apart.
     */
    private static ActionItem info(String code, Map<String, String> params, String text) {
        return ActionItem.builder().text(text).code(code).params(params).build();
    }

    /** Map.of rejects null values, and a counterparty name reaches us from user data. */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static ActionItem payBank(MonthPaid monthPaid, BigDecimal target) {
        return ActionItem.builder()
                .text("Pay bank installments this month.")
                .code("page.plan.actionPayBank")
                .params(Map.of())
                .action("PAY_BANK")
                .paid(monthPaid.bankInstallments())
                .target(target)
                .unlockThreshold(bankUnlockThreshold(target))
                .build();
    }

    /**
     * The debt asks shared by every level: the set-aside for MONTHLY loans (their plans), then the
     * pay-back of everything ASAP — both paid through PayPersonalLoanModal. The set-aside is money
     * the user committed to themselves and the pay-back is the ASAP rule's demand on the rest;
     * showing them as one number hid the plan entirely.
     *
     * @param debt34Uzs this month's whole debt charge: the MONTHLY plans plus every ASAP ask (see
     *        {@link #debtAsks})
     */
    private static void addDebtActions(List<ActionItem> actions, BigDecimal debt34Uzs,
                                       BigDecimal plannedSetAsideUzs, Currency cur, MonthPaid monthPaid) {
        if (plannedSetAsideUzs.signum() > 0) {
            actions.add(setAside(plannedSetAsideUzs, cur, monthPaid));
        }
        BigDecimal ruleTarget = debt34Uzs.subtract(plannedSetAsideUzs);
        if (ruleTarget.signum() > 0) {
            actions.add(payDebts34(ruleTarget, cur, monthPaid));
        }
    }

    /**
     * The ASAP pay-back: every ASAP loan's and debt's ask this month — all of what was left at the
     * month's start when that is at most 70% of the stable income, else 34% of it. It must reach the
     * full target to unlock. The code keeps its old name ("payDebts34"): the bot translates it.
     */
    private static ActionItem payDebts34(BigDecimal target, Currency cur, MonthPaid monthPaid) {
        String amount = formatNumber(target) + " " + cur;
        return ActionItem.builder()
                .text("Pay back your debts / borrowed money as fast as you can (~ " + amount + ") this month.")
                .code("page.plan.action.payDebts34")
                .params(Map.of("amount", amount))
                .action("PAY_PERSONAL_LOAN")
                // Only repayments toward these loans and debts: money paid toward a planned loan
                // counts for the set-aside and must not satisfy this ask as well.
                .paid(monthPaid.ruleRepayments())
                .target(target)
                .unlockThreshold(target)
                .build();
    }

    /**
     * The repayment plan as its own allocation ask. Same PAY_PERSONAL_LOAN action (it is paid
     * the same way), but worded as the commitment the user made rather than as the ASAP rule.
     */
    private static ActionItem setAside(BigDecimal targetDisplay, Currency displayCurrency,
                                       MonthPaid monthPaid) {
        String amount = formatNumber(targetDisplay) + " " + displayCurrency;
        return ActionItem.builder()
                .text("Set aside " + amount + " this month for your loan repayment plan.")
                .code("page.plan.action.setAside")
                .params(Map.of("amount", amount))
                .action("PAY_PERSONAL_LOAN")
                // Only repayments toward loans on a plan — see payDebts34.
                .paid(monthPaid.planRepayments())
                .target(targetDisplay)
                .unlockThreshold(targetDisplay)
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
     * Build the three bucket lines (Donation / Emergency / Investments) from minimum-percent
     * strings. Pass null for a bucket that should render as "NO NEED" at this tier. Paid-this-month
     * amounts come from the BucketPaid context.
     *
     * <p>Stocks are no longer an allocation bucket — the owner does not allocate to stocks at all.
     * The percentage is still computed upstream (it is part of the Level-1 scenario tuple) but
     * nothing is emitted for it, so it cannot appear in the Overview, the ledger, or a month.
     */
    private List<AllocationLine> percentLines(BigDecimal incomeUzs, Currency displayCurrency,
                                              BucketPaid paid, BucketPaid marks,
                                              String donationPct, String emergencyPct,
                                              String investmentsPct) {
        List<AllocationLine> lines = new ArrayList<>(3);
        lines.add(line("DONATION",    "Donation",    donationPct,    incomeUzs, displayCurrency,
                paid.donation(), marks.donation()));
        lines.add(line("EMERGENCY",   "Emergency",   emergencyPct,   incomeUzs, displayCurrency,
                paid.emergency(), marks.emergency()));
        lines.add(line("INVESTMENTS", "Investments", investmentsPct, incomeUzs, displayCurrency,
                paid.investments(), marks.investments()));
        return lines;
    }

    private AllocationLine line(String bucket, String label, String pctStr,
                                BigDecimal incomeUzs, Currency displayCurrency,
                                BigDecimal paidDisplay, BigDecimal markedDisplay) {
        if (pctStr == null) {
            // Not recommended at this tier. Still surface paidAmount so the frontend can
            // tell the user "you paid X here even though it's not recommended this month".
            return AllocationLine.builder()
                    .bucket(bucket).label(label).recommended(false)
                    .minPercent(null).minAmount(null)
                    .paidAmount(paidDisplay).markedAmount(markedDisplay)
                    .paidPercent(null).remainingAmount(null)
                    .build();
        }
        BigDecimal pct = new BigDecimal(pctStr);
        BigDecimal amountUzs = incomeUzs.multiply(pct, MC).divide(new BigDecimal("100"), MC);
        BigDecimal minAmount = amountUzs;

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
                .markedAmount(markedDisplay)
                .paidPercent(paidPercent)
                .remainingAmount(remaining)
                .build();
    }

    private TierAllocation notDefinedAllocation(String code, Map<String, String> params, String note) {
        return TierAllocation.builder()
                .scenarioKey(null)
                .scenarioLabel("Guidance not yet defined for this tier")
                .lines(List.of())
                .actions(List.of(info(code, params, note)))
                .build();
    }

    /** "June 2026" — for the "allocation tracking starts …" guidance note. */
    private static String monthLabel(YearMonth ym) {
        return ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                + " " + ym.getYear();
    }

    private static String formatNumber(BigDecimal n) {
        if (n == null) return "0";
        // Cheap thousand-grouping. Detail is fine since notes are short scenario hints.
        return n.setScale(0, RoundingMode.HALF_UP).toPlainString()
                .replaceAll("(\\d)(?=(\\d{3})+$)", "$1 "); // thin space
    }
}
