package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Breakdown;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Daily;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.IncomePart;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.ShortBy;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Upcoming;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse.PendingSubscription;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.MonthlyPaymentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * "How much can I spend a day without running short, counting my next salaries, bills, loan
 * payments and savings — and how does that compare with what I actually spend?"
 *
 * <p>The advisor used to answer with one month-scoped "free" figure and assumed a month's living
 * costs were whatever the plan left over. Both were too optimistic: money that is free today can
 * be the rent on the 10th, and the owner spends far more than the plan's leftover. This service
 * instead walks the days from today to the end of a horizon that always contains the next salary
 * and the whole cycle after it, and finds the day money is tightest.
 *
 * <p><b>Today is the dividing line.</b> {@code have} is the wallets as of today, so only rows dated
 * on or before today count as paid or received. A row the owner recorded for a later day this month
 * — a rent paid "on the 28th", a salary entered ahead — has not moved any money yet: it enters the
 * walk on its own day instead.
 *
 * <ol>
 *   <li><b>Income, conservatively.</b> The salary's shape (which days, how much) comes from the
 *       most recent COMPLETE month, up to {@value #SALARY_LOOKBACK_MONTHS} back, whose non-bonus
 *       regular income reached half the stable income — this month's own only when no complete
 *       month has one, since a month in progress passes half with its first part and would drop
 *       the later ones. Parts are transactions of at least a tenth of the stable income, grouped
 *       by day of month and scaled down so a month never projects more than Settings says. This
 *       month, what has arrived is matched against the parts in day order, and only what is still
 *       missing of each part is expected — on that part's day, never in all more than
 *       {@code salaryComing}. A part whose day has passed without it is left out (it cannot be
 *       dated). With no history: the stable income on the 1st of every coming month.</li>
 *   <li><b>Horizon.</b> The day before the second main payday after today (the main payday is
 *       the day of the largest part) — or the end of next month without a salary history.</li>
 *   <li><b>Must-pays.</b> Bills on their due day, bank installments on the day the loan was
 *       taken, borrowed money and debts on their payment-start day at the Plan's monthly ask
 *       (the repayment plan, else 34% of the original) capped at what is left. This month's are
 *       counted paid the way the Plan counts them, as of today; unpaid ones whose day is past are
 *       due today.</li>
 *   <li><b>Savings, out of the salary that funds them.</b> This month's unset-aside buckets on this
 *       month's main payday while its main part is still to come, else today; on each later
 *       month's main payday (the 1st without a history), the current level's percentages of that
 *       month's projected salary after bills and debt.</li>
 *   <li><b>Per day.</b> safePerDay = min over the horizon of net(c) ÷ days to c, rounded down to
 *       1,000; zero, with {@code shortBy}, when some day's net is below zero.</li>
 *   <li><b>Pace.</b> Everyday spending over the last 30 days (never before tracking started) —
 *       what is left after bills, loans, savings, investments, lending and transfers — per day,
 *       the first day that pace would leave a payment unmet, and the least that would ever be left
 *       over at it (the surplus the advisor may suggest putting away).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class DailyAdviceService {

    /** A month counts as "the salary arrived" once non-bonus regular income reached this share of the stable income. */
    static final BigDecimal SALARY_MONTH_SHARE = new BigDecimal("0.50");
    /** A single income is a salary part only from this share of the stable income — a 100k refund is not. */
    static final BigDecimal SALARY_PART_SHARE = new BigDecimal("0.10");
    /** Complete months before the current one searched for the salary's shape. */
    static final int SALARY_LOOKBACK_MONTHS = 3;
    /** How far ahead {@link Daily#getUpcoming()} lists must-pays, even past the horizon. */
    static final int UPCOMING_DAYS = 34;
    /** The pace window: the last 30 days, today included. */
    static final int PACE_WINDOW_DAYS = 30;
    /** Below this many days of data a pace is a guess, so none is given. */
    static final int PACE_MIN_DAYS = 7;
    static final String SALARY = "Salary";

    static final String BILL = "BILL";
    static final String BANK = "BANK";
    static final String LOAN = "LOAN";
    static final String DEBT = "DEBT";

    /** Rows that move money into an allocation bucket — savings, not spending. */
    private static final Set<TransactionSubType> SAVING_SUB_TYPES = Set.of(TransactionSubType.DONATION,
            TransactionSubType.EMERGENCY_CONTRIBUTION, TransactionSubType.INVESTMENT, TransactionSubType.STOCK_PURCHASE);

    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OverviewService overviewService;
    private final SettingsService settingsService;
    private final TransactionRepository transactionRepository;
    private final MonthlyPaymentRepository monthlyPaymentRepository;
    private final BankLoanRepository bankLoanRepository;
    private final LoanTakenRepository loanTakenRepository;
    private final DebtRepository debtRepository;

    /**
     * What the advisor has already worked out for today.
     *
     * @param have         Σ of the UZS wallets as of today
     * @param stableIncome the monthly stable income from Settings (positive)
     * @param salaryComing what this month's salary still owes: stable income − non-bonus regular income this month
     * @param setAsideLeft this month's set-asides not yet made
     * @param pctSum       the current level's bucket percentages added up (Level 1 tight: 5 + 2 + 8)
     * @param goals        savings goals with a monthly payment, still short of their target
     */
    public record Inputs(LocalDate today, BigDecimal have, BigDecimal stableIncome, BigDecimal salaryComing,
                         BigDecimal setAsideLeft, BigDecimal pctSum, List<Goal> goals) {
        /** With no savings goal to set aside for. */
        public Inputs(LocalDate today, BigDecimal have, BigDecimal stableIncome, BigDecimal salaryComing,
                      BigDecimal setAsideLeft, BigDecimal pctSum) {
            this(today, have, stableIncome, salaryComing, setAsideLeft, pctSum, List.of());
        }
    }

    /**
     * A savings goal's monthly payment, set aside like the plan's buckets (though outside their
     * percentages). Its deadline plays no part: it is for display.
     *
     * @param monthly       the monthly payment (positive)
     * @param paidThisMonth contributions to it this month, dated today or earlier
     * @param toTarget      what is still missing to reach its target; null when it has none
     */
    public record Goal(Long id, BigDecimal monthly, BigDecimal paidThisMonth, BigDecimal toTarget) {}

    /**
     * @param surplus the least the owner would ever have in hand beyond their own pace: the minimum,
     *                over every day d from today to {@code until}, of net(d) − paceDaily × days(d).
     *                Measured on every day, not only the last — a dip before payday is money that is
     *                not spare. Null when there is no pace. Drives the advisor's "put some of it
     *                away?" idea; not part of the response.
     */
    public record Result(Daily daily, BigDecimal surplus) {}

    @Transactional(readOnly = true)
    public Result compute(Inputs in) {
        LocalDate today = in.today();
        BigDecimal stable = nz(in.stableIncome());
        if (today == null || stable.signum() <= 0) return null;
        YearMonth current = YearMonth.from(today);
        BigDecimal have = nz(in.have());
        List<Transaction> later = laterThisMonth(today);

        // ── 1–2. Income and the horizon ──
        List<Transaction> salaryThisMonth = salaryRows(current);
        SalaryPattern pattern = salaryPattern(current, stable, salaryThisMonth);
        LocalDate until = pattern == null ? current.plusMonths(1).atEndOfMonth() : horizonEnd(pattern.mainDay(), today);
        Salary salary = projectSalary(pattern, today, until, stable, nz(in.salaryComing()), salaryThisMonth);
        List<IncomePart> incomes = salary.incomes();

        // ── 3. Must-pays: through the end of the horizon's month, and at least a listing's worth ──
        LocalDate listTo = today.plusDays(UPCOMING_DAYS);
        LocalDate computeTo = YearMonth.from(until).atEndOfMonth();
        if (listTo.isAfter(computeTo)) computeTo = listTo;
        Obligations due = obligations(today, computeTo, later);

        // ── 4. Savings, set aside out of the salary that funds them ──
        NavigableMap<LocalDate, BigDecimal> savings = new TreeMap<>();
        // Reserved before the salary arrives, they read as "short" every month until payday.
        LocalDate thisMonthOn = salary.mainPartOn() != null ? salary.mainPartOn() : today;
        BigDecimal setAsideLeft = nz(in.setAsideLeft());
        if (setAsideLeft.signum() > 0) savings.merge(thisMonthOn, setAsideLeft, BigDecimal::add);
        for (Transaction t : later) {
            if (isSaving(t)) savings.merge(t.getTransactionDate(), t.getAmount(), BigDecimal::add);
        }
        // Each later month in the horizon, on its main payday (the 1st without a salary history).
        List<LocalDate> laterPaydays = new ArrayList<>();
        List<YearMonth> laterMonths = new ArrayList<>();
        for (YearMonth ym = current.plusMonths(1); !ym.atDay(1).isAfter(until); ym = ym.plusMonths(1)) {
            LocalDate on = pattern == null ? ym.atDay(1) : dayIn(ym, pattern.mainDay());
            if (on.isAfter(until)) continue;
            laterPaydays.add(on);
            laterMonths.add(ym);
        }
        BigDecimal pct = nz(in.pctSum());
        if (pct.signum() > 0) {
            BigDecimal monthlySalary = pattern == null ? stable : pattern.total();
            for (int i = 0; i < laterMonths.size(); i++) {
                YearMonth ym = laterMonths.get(i);
                // The Plan's base without a bonus: salary − bills − (bank installments + debt asks).
                BigDecimal base = clampZero(monthlySalary.subtract(due.billsMonthly())
                        .subtract(due.bankByMonth().getOrDefault(ym, BigDecimal.ZERO))
                        .subtract(due.asksByMonth().getOrDefault(ym, BigDecimal.ZERO)));
                BigDecimal estimate = base.multiply(pct).divide(HUNDRED, 0, RoundingMode.HALF_UP);
                if (estimate.signum() > 0) savings.merge(laterPaydays.get(i), estimate, BigDecimal::add);
            }
        }
        // Savings goals' monthly payments, on the same days: what this month still owes (less what
        // is recorded for a later day — the walk takes that on its own day), then the full payment
        // each later month; all of it capped at what is still missing to reach the target.
        for (Goal g : in.goals() == null ? List.<Goal>of() : in.goals()) {
            BigDecimal monthly = nz(g.monthly());
            if (monthly.signum() <= 0) continue;
            BigDecimal recordedLater = later.stream()
                    .filter(t -> isSaving(t) && Objects.equals(t.getInvestmentId(), g.id()))
                    .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cap = g.toTarget();
            cap = reserve(savings, thisMonthOn,
                    clampZero(monthly.subtract(nz(g.paidThisMonth())).subtract(recordedLater)), cap);
            for (LocalDate on : laterPaydays) cap = reserve(savings, on, monthly, cap);
        }

        // ── 5–7. Walk the days ──
        NavigableMap<LocalDate, BigDecimal> change = new TreeMap<>();
        for (IncomePart i : incomes) change.merge(i.getDate(), i.getAmount(), BigDecimal::add);
        for (Upcoming u : due.items()) {
            if (!u.getDate().isAfter(until)) change.merge(u.getDate(), u.getAmount().negate(), BigDecimal::add);
        }
        savings.forEach((d, a) -> change.merge(d, a.negate(), BigDecimal::add));

        Pace pace = pace(today);
        BigDecimal paceDaily = pace == null ? null : pace.perDay();

        BigDecimal net = have;
        BigDecimal minRatio = null, minNet = null, minHeadroom = null;
        LocalDate minRatioOn = null, minNetOn = null, runsOutOn = null;
        for (LocalDate d = today; !d.isAfter(until); d = d.plusDays(1)) {
            net = net.add(change.getOrDefault(d, BigDecimal.ZERO));
            BigDecimal days = BigDecimal.valueOf(daysFrom(today, d));
            BigDecimal ratio = net.divide(days, 4, RoundingMode.FLOOR);
            if (minRatio == null || ratio.compareTo(minRatio) < 0) {
                minRatio = ratio;
                minRatioOn = d;
            }
            if (minNet == null || net.compareTo(minNet) < 0) {
                minNet = net;
                minNetOn = d;
            }
            if (paceDaily != null) {
                // What would be left on d, having spent at the owner's pace every day until then.
                BigDecimal headroom = net.subtract(paceDaily.multiply(days));
                if (runsOutOn == null && headroom.signum() < 0) runsOutOn = d;
                if (minHeadroom == null || headroom.compareTo(minHeadroom) < 0) minHeadroom = headroom;
            }
        }

        boolean isShort = minNet.signum() < 0;
        BigDecimal safePerDay = isShort ? BigDecimal.ZERO
                : minRatio.divide(THOUSAND, 0, RoundingMode.FLOOR).multiply(THOUSAND);
        // Short: the day the money is lowest is the one worth explaining, so the breakdown shows it.
        LocalDate tightestOn = isShort ? minNetOn : minRatioOn;

        Daily daily = Daily.builder()
                .safePerDay(safePerDay)
                .until(until)
                .tightestOn(tightestOn)
                .breakdown(breakdown(have, incomes, due.items(), savings, today, tightestOn))
                .paceDaily(paceDaily)
                .paceFrom(pace == null ? null : pace.from())
                .paceTo(pace == null ? null : pace.to())
                .runsOutOn(runsOutOn)
                .shortBy(isShort ? ShortBy.builder().date(minNetOn).amount(minNet.negate()).build() : null)
                .upcoming(due.items().stream().filter(u -> !u.getDate().isAfter(listTo)).toList())
                .incomes(incomes)
                .build();
        return new Result(daily, minHeadroom);
    }

    /**
     * This month's rows dated after today, oldest first. They have not moved any money yet — the
     * wallets are as of today — so the walk takes each on its own day rather than as already done.
     */
    private List<Transaction> laterThisMonth(LocalDate today) {
        LocalDate monthEnd = YearMonth.from(today).atEndOfMonth();
        if (!today.isBefore(monthEnd)) return List.of();
        List<Transaction> rows = transactionRepository.findByTransactionDateBetween(today.plusDays(1), monthEnd);
        if (rows == null) return List.of();
        return rows.stream()
                .filter(t -> isUzs(t.getCurrency()) && t.getAmount() != null && t.getAmount().signum() > 0)
                .sorted(Comparator.comparing(Transaction::getTransactionDate)
                        .thenComparing(Transaction::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    // ── Income ────────────────────────────────────────────────────────────────

    /**
     * The salary's shape: day of month → amount, and the day of the largest part. Package-private
     * for tests.
     */
    record SalaryPattern(NavigableMap<Integer, BigDecimal> parts) {
        BigDecimal total() {
            return parts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** The day of the largest part; the earlier day on a tie. */
        int mainDay() {
            int day = parts.firstKey();
            for (Map.Entry<Integer, BigDecimal> p : parts.entrySet()) {
                if (p.getValue().compareTo(parts.get(day)) > 0) day = p.getKey();
            }
            return day;
        }
    }

    /**
     * The salary's shape from the most recent complete month it arrived in — this month's own only
     * when none of the complete months has one. A month in progress passes half the stable income
     * with its first part, and taking its shape then would drop every later part from the months
     * projected after it. Null: the fallback.
     */
    private SalaryPattern salaryPattern(YearMonth current, BigDecimal stable, List<Transaction> thisMonth) {
        for (int back = 1; back <= SALARY_LOOKBACK_MONTHS; back++) {
            SalaryPattern pattern = patternOf(salaryRows(current.minusMonths(back)), stable);
            if (pattern != null) return pattern;
        }
        return patternOf(thisMonth, stable);
    }

    /** A month's salary as parts by day, or null when it is not "the salary arrived". */
    private static SalaryPattern patternOf(List<Transaction> salary, BigDecimal stable) {
        BigDecimal total = salary.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(stable.multiply(SALARY_MONTH_SHARE)) < 0) return null;
        BigDecimal partFloor = stable.multiply(SALARY_PART_SHARE);
        NavigableMap<Integer, BigDecimal> parts = new TreeMap<>();
        for (Transaction t : salary) {
            if (t.getAmount().compareTo(partFloor) < 0) continue;
            parts.merge(t.getTransactionDate().getDayOfMonth(), t.getAmount(), BigDecimal::add);
        }
        return parts.isEmpty() ? null : new SalaryPattern(capAt(parts, stable));
    }

    /** A month's salary: the population of salaryReceived — regular income, bonus categories left out. */
    private List<Transaction> salaryRows(YearMonth ym) {
        List<Transaction> rows = transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                TransactionSubType.REGULAR_INCOME, ym.atDay(1), ym.atEndOfMonth());
        if (rows == null) return List.of();
        return rows.stream()
                .filter(t -> t.getType() != TransactionType.EXPENSE && isUzs(t.getCurrency())
                        && t.getAmount() != null && t.getAmount().signum() > 0 && !isBonus(t))
                .toList();
    }

    /**
     * Never project more than the stable income: parts adding up to more are scaled down to it,
     * in whole thousands (largest remainders get the leftover thousands, so the total lands on the
     * stable income, never above it). Parts within it stay as they were received.
     */
    static NavigableMap<Integer, BigDecimal> capAt(NavigableMap<Integer, BigDecimal> parts, BigDecimal stable) {
        BigDecimal total = parts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(stable) <= 0) return parts;
        NavigableMap<Integer, BigDecimal> scaled = new TreeMap<>();
        Map<Integer, BigDecimal> remainder = new HashMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> p : parts.entrySet()) {
            BigDecimal exact = p.getValue().multiply(stable).divide(total, 6, RoundingMode.DOWN);
            BigDecimal floor = exact.divide(THOUSAND, 0, RoundingMode.DOWN).multiply(THOUSAND);
            scaled.put(p.getKey(), floor);
            remainder.put(p.getKey(), exact.subtract(floor));
            sum = sum.add(floor);
        }
        int spare = stable.subtract(sum).divide(THOUSAND, 0, RoundingMode.DOWN).intValue();
        List<Integer> byRemainder = new ArrayList<>(scaled.keySet());
        byRemainder.sort(Comparator.comparing((Integer d) -> remainder.get(d)).reversed()
                .thenComparing(Comparator.naturalOrder()));
        for (int i = 0; i < spare && i < byRemainder.size(); i++) {
            scaled.merge(byRemainder.get(i), THOUSAND, BigDecimal::add);
        }
        return scaled;
    }

    /** The day before the second main payday that is still ahead of today. */
    static LocalDate horizonEnd(int mainDay, LocalDate today) {
        YearMonth ym = YearMonth.from(today);
        LocalDate first = dayIn(ym, mainDay);
        if (!first.isAfter(today)) first = dayIn(ym.plusMonths(1), mainDay);
        return dayIn(YearMonth.from(first).plusMonths(1), mainDay).minusDays(1);
    }

    /**
     * @param incomes    projected income in [today, until], one entry a day, date ascending
     * @param mainPartOn the day this month's main part is still expected on, or null when it has
     *                   come (or cannot be dated) — this month's savings wait for it
     */
    record Salary(List<IncomePart> incomes, LocalDate mainPartOn) {}

    /**
     * Projected income in [today, until].
     *
     * <p>This month: a row recorded for a later day is income on that day. Then what the month has
     * brought — received or recorded ahead — is matched against the usual parts in day order, and
     * only what is still missing of each part is expected, on its own day, never more in all than
     * {@code salaryComing}. A part whose day has passed without it is left out: it cannot be dated,
     * and "today" would be a guess (it is expected on its day again from next month). Without a
     * salary history, {@code salaryComing} falls on the month's last day.
     *
     * <p>Later months: each part on its day — or, without a history, the stable income on the 1st.
     */
    private static Salary projectSalary(SalaryPattern pattern, LocalDate today, LocalDate until, BigDecimal stable,
                                 BigDecimal salaryComing, List<Transaction> thisMonth) {
        YearMonth current = YearMonth.from(today);
        NavigableMap<LocalDate, BigDecimal> byDay = new TreeMap<>();
        LocalDate mainPartOn = null;

        for (Transaction t : thisMonth) {
            if (t.getTransactionDate().isAfter(today)) byDay.merge(t.getTransactionDate(), t.getAmount(), BigDecimal::add);
        }
        if (pattern != null) {
            BigDecimal arrived = thisMonth.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cap = salaryComing;
            for (Map.Entry<Integer, BigDecimal> part : pattern.parts().entrySet()) {
                BigDecimal covered = arrived.min(part.getValue());
                arrived = arrived.subtract(covered);
                LocalDate on = dayIn(current, part.getKey());
                BigDecimal missing = part.getValue().subtract(covered).min(cap);
                if (missing.signum() <= 0 || on.isBefore(today)) continue;
                byDay.merge(on, missing, BigDecimal::add);
                cap = cap.subtract(missing);
                if (part.getKey() == pattern.mainDay()) mainPartOn = on;
            }
        } else if (salaryComing.signum() > 0) {
            byDay.merge(current.atEndOfMonth(), salaryComing, BigDecimal::add);
            mainPartOn = current.atEndOfMonth();
        }

        for (YearMonth ym = current.plusMonths(1); !ym.atDay(1).isAfter(until); ym = ym.plusMonths(1)) {
            if (pattern == null) {
                byDay.merge(ym.atDay(1), stable, BigDecimal::add);
                continue;
            }
            for (Map.Entry<Integer, BigDecimal> part : pattern.parts().entrySet()) {
                LocalDate date = dayIn(ym, part.getKey());
                if (!date.isAfter(until)) byDay.merge(date, part.getValue(), BigDecimal::add);
            }
        }
        List<IncomePart> out = new ArrayList<>();
        byDay.forEach((date, amount) -> {
            if (!date.isAfter(until) && amount.signum() > 0) {
                out.add(IncomePart.builder().date(date).name(SALARY).amount(amount).build());
            }
        });
        return new Salary(out, mainPartOn);
    }

    // ── Must-pays ─────────────────────────────────────────────────────────────

    /**
     * Every must-pay from today to {@code to}, date ascending; plus, for the savings estimate,
     * the monthly bills and each later month's bank installments and loan/debt asks.
     */
    record Obligations(List<Upcoming> items, BigDecimal billsMonthly,
                       Map<YearMonth, BigDecimal> bankByMonth, Map<YearMonth, BigDecimal> asksByMonth) {}

    private Obligations obligations(LocalDate today, LocalDate to, List<Transaction> later) {
        List<Upcoming> items = new ArrayList<>();
        // Paid by today: a payment dated later this month is still in the wallets.
        OverviewService.MonthPaid paid = overviewService.computeMonthPaid(YearMonth.from(today), Currency.UZS, today);
        BigDecimal billsMonthly = bills(today, to, later, items);
        Map<YearMonth, BigDecimal> bankByMonth = new HashMap<>();
        bankLoans(today, to, paid == null ? BigDecimal.ZERO : nz(paid.bankInstallments()), later, items, bankByMonth);
        Map<YearMonth, BigDecimal> asksByMonth = new HashMap<>();
        loansAndDebts(today, to, paid, later, items, asksByMonth);
        items.sort(Comparator.comparing(Upcoming::getDate)
                .thenComparing(Upcoming::getAmount, Comparator.reverseOrder())
                .thenComparing(Upcoming::getKind)
                .thenComparing(Upcoming::getRefId, Comparator.nullsLast(Comparator.naturalOrder())));
        return new Obligations(items, billsMonthly, bankByMonth, asksByMonth);
    }

    /** One of this month's asks while it is being placed: what it still owes, and when that falls due. */
    private static final class Ask {
        final String kind;
        final Long refId;
        final String name;
        final LocalDate dueOn;
        /** A 34%-rule ask, where a repayment naming no loan is counted (as the Plan counts it). */
        final boolean rule;
        BigDecimal owed;

        Ask(String kind, Long refId, String name, LocalDate dueOn, BigDecimal owed, boolean rule) {
            this.kind = kind;
            this.refId = refId;
            this.name = name;
            this.dueOn = dueOn;
            this.owed = owed;
            this.rule = rule;
        }
    }

    /**
     * A payment recorded for a later day this month: paid on its own day, in full — that is when the
     * money leaves the wallet — against {@code ask}, whose still-owed amount it reduces. Listed as
     * {@code recorded}, so no client offers to pay it a second time.
     */
    private static void paidLater(List<Upcoming> out, Ask ask, Transaction t) {
        out.add(Upcoming.builder().date(t.getTransactionDate()).kind(ask.kind).refId(ask.refId)
                .name(ask.name == null ? "" : ask.name).amount(t.getAmount()).recorded(true).build());
        ask.owed = clampZero(ask.owed.subtract(t.getAmount()));
    }

    /** What an ask still owes after its later payments: due on its day, or today once that has passed. */
    private static void owing(List<Upcoming> out, LocalDate today, Ask ask) {
        if (ask.owed.signum() > 0) out.add(due(today, ask.dueOn, ask.kind, ask.refId, ask.name, ask.owed));
    }

    /** The first ask still owing, else the last one; null when there is none. */
    private static Ask firstOwing(List<Ask> asks) {
        return asks.stream().filter(a -> a.owed.signum() > 0).findFirst()
                .orElse(asks.isEmpty() ? null : asks.getLast());
    }

    /**
     * Active bills on their due day. This month's owes what the advisor's bill list says is left —
     * payments linked to the bill and "already paid" marks — counting payments made by today; one
     * recorded for a later day is paid on that day, and the rest falls due today once its day has
     * passed. Returns Σ of the monthly amounts.
     */
    private BigDecimal bills(LocalDate today, LocalDate to, List<Transaction> later, List<Upcoming> out) {
        YearMonth current = YearMonth.from(today);
        Map<Long, BigDecimal> leftThisMonth = new HashMap<>();
        List<PendingSubscription> pending = overviewService.pendingSubscriptions(current, today);
        if (pending != null) {
            for (PendingSubscription p : pending) {
                leftThisMonth.put(p.getId(), clampZero(nz(p.getAmount()).subtract(nz(p.getPaid()))));
            }
        }
        BigDecimal monthly = BigDecimal.ZERO;
        for (MonthlyPayment m : monthlyPaymentRepository.findAll()) {
            if (!Boolean.TRUE.equals(m.getActive()) || !isUzs(m.getCurrency())) continue;
            BigDecimal amount = nz(m.getAmount());
            if (amount.signum() <= 0) continue;
            monthly = monthly.add(amount);
            int day = m.getDueDay() == null ? 1 : m.getDueDay();
            Ask ask = new Ask(BILL, m.getId(), m.getName(), dayIn(current, day),
                    leftThisMonth.getOrDefault(m.getId(), BigDecimal.ZERO), false);
            for (Transaction t : later) {
                if (Objects.equals(t.getMonthlyPaymentId(), m.getId())) paidLater(out, ask, t);
            }
            owing(out, today, ask);
            for (YearMonth ym = current.plusMonths(1); !ym.atDay(1).isAfter(to); ym = ym.plusMonths(1)) {
                LocalDate date = dayIn(ym, day);
                if (!date.isAfter(to)) out.add(item(date, BILL, m.getId(), m.getName(), amount, false));
            }
        }
        return monthly;
    }

    /**
     * Bank installments on the day of month the loan was taken, in every month it runs (the Plan's
     * test), never after its end date. This month's are covered by what the Plan counts as paid by
     * today — installments recorded plus "already paid" marks — applied to the loans in due order, so
     * the rows add up to the advisor's BANK bill. An installment recorded for a later day names no
     * loan: it is paid on its day against the first one still owing.
     */
    private void bankLoans(LocalDate today, LocalDate to, BigDecimal paidByToday, List<Transaction> later,
                           List<Upcoming> out, Map<YearMonth, BigDecimal> bankByMonth) {
        YearMonth current = YearMonth.from(today);
        List<BankLoan> loans = bankLoanRepository.findAll().stream()
                .filter(b -> b.getMonthlyPayment() != null && b.getMonthlyPayment().signum() > 0
                        && isUzs(b.getCurrency()))
                .sorted(Comparator.comparing(DailyAdviceService::installmentDay)
                        .thenComparing(BankLoan::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<Ask> asks = new ArrayList<>();
        BigDecimal paid = paidByToday;
        for (BankLoan b : loans) {
            if (!OverviewService.bankLoanRunsIn(b, current)) continue;
            BigDecimal covered = paid.min(b.getMonthlyPayment());
            paid = paid.subtract(covered);
            asks.add(new Ask(BANK, b.getId(), bankName(b), installmentDate(b, current),
                    b.getMonthlyPayment().subtract(covered), false));
        }
        for (Transaction t : later) {
            if (t.getType() != TransactionType.EXPENSE || t.getSubType() != TransactionSubType.BANK_LOAN_PAYMENT) continue;
            Ask ask = firstOwing(asks);
            paidLater(out, ask != null ? ask : new Ask(BANK, null, t.getDescription(), null, BigDecimal.ZERO, false), t);
        }
        for (Ask a : asks) owing(out, today, a);
        for (YearMonth ym = current.plusMonths(1); !ym.atDay(1).isAfter(to); ym = ym.plusMonths(1)) {
            for (BankLoan b : loans) {
                if (!OverviewService.bankLoanRunsIn(b, ym)) continue;
                bankByMonth.merge(ym, b.getMonthlyPayment(), BigDecimal::add);
                LocalDate date = installmentDate(b, ym);
                if (!date.isAfter(to)) out.add(item(date, BANK, b.getId(), bankName(b), b.getMonthlyPayment(), false));
            }
        }
    }

    /** One borrowed loan or debt, as far as its monthly asks are concerned. */
    private record Owed(String kind, Long id, String name, BigDecimal total, BigDecimal left,
                        BigDecimal planned, LocalDate start, int day) {
        /** The Plan's monthly ask with {@code remaining} still owed: the plan, else 34% of the original — capped. */
        BigDecimal ask(BigDecimal remaining) {
            if (planned != null) return planned.min(remaining);
            return OverviewService.debtMonthlyCharge(total, total.subtract(remaining));
        }
    }

    /**
     * Borrowed money and debts that are not PAID: each month's ask from the month its payments
     * start, on its payment-start day (the 1st when unset), the running total capped at what is
     * left. This month's asks are covered by what the Plan counts as paid by today — repayments to
     * loans on a plan for the plan's asks, every other repayment and mark for the 34% asks. A
     * repayment recorded for a later day is paid on its day, against its own loan or debt (one that
     * names neither goes to the first 34% ask still owing).
     */
    private void loansAndDebts(LocalDate today, LocalDate to, OverviewService.MonthPaid paid,
                               List<Transaction> later, List<Upcoming> out, Map<YearMonth, BigDecimal> asksByMonth) {
        YearMonth current = YearMonth.from(today);
        List<Owed> owed = new ArrayList<>();
        for (LoanTaken l : loanTakenRepository.findAll()) {
            if (l.getStatus() == RecordStatus.PAID || !isUzs(l.getCurrency())) continue;
            BigDecimal total = nz(l.getTotalAmount());
            BigDecimal left = total.subtract(nz(l.getPaidAmount()));
            if (left.signum() <= 0) continue;
            // The PLANNED payment, never the legacy monthlyPayment column (derived at creation).
            BigDecimal planned = l.getPlannedMonthlyPayment() != null && l.getPlannedMonthlyPayment().signum() > 0
                    ? l.getPlannedMonthlyPayment() : null;
            owed.add(new Owed(LOAN, l.getId(), l.getLenderName(), total, left, planned,
                    l.getPaymentStartDate(), startDay(l.getPaymentStartDate())));
        }
        for (Debt d : debtRepository.findAll()) {
            if (d.getStatus() == RecordStatus.PAID || !isUzs(d.getCurrency())) continue;
            BigDecimal total = nz(d.getTotalAmount());
            BigDecimal left = total.subtract(nz(d.getPaidAmount()));
            if (left.signum() <= 0) continue;
            owed.add(new Owed(DEBT, d.getId(), d.getCreditorName(), total, left, null,
                    d.getPaymentStartDate(), startDay(d.getPaymentStartDate())));
        }
        owed.sort(Comparator.comparingInt(Owed::day).thenComparing(Owed::kind, Comparator.reverseOrder())
                .thenComparing(Owed::id, Comparator.nullsLast(Comparator.naturalOrder())));

        BigDecimal planPaid = paid == null ? BigDecimal.ZERO : nz(paid.planRepayments());
        BigDecimal rulePaid = paid == null ? BigDecimal.ZERO : nz(paid.ruleRepayments());
        List<Ask> asks = new ArrayList<>(owed.size());
        for (Owed o : owed) {
            BigDecimal owes = BigDecimal.ZERO;
            if (OverviewService.hasStartedBy(o.start(), current)) {
                BigDecimal ask = o.ask(o.left());
                BigDecimal covered;
                if (o.planned() != null) {
                    covered = planPaid.min(ask);
                    planPaid = planPaid.subtract(covered);
                } else {
                    covered = rulePaid.min(ask);
                    rulePaid = rulePaid.subtract(covered);
                }
                owes = ask.subtract(covered);
            }
            asks.add(new Ask(o.kind(), o.id(), o.name(), dayIn(current, o.day()), owes, o.planned() == null));
        }
        for (Transaction t : later) {
            if (t.getType() != TransactionType.EXPENSE || t.getSubType() != TransactionSubType.LOAN_REPAYMENT) continue;
            String kind = t.getRepaidLoanTakenId() != null ? LOAN : t.getRepaidDebtId() != null ? DEBT : null;
            Long ref = t.getRepaidLoanTakenId() != null ? t.getRepaidLoanTakenId() : t.getRepaidDebtId();
            Ask ask = kind == null
                    ? firstOwing(asks.stream().filter(a -> a.rule).toList())
                    : asks.stream().filter(a -> a.kind.equals(kind) && Objects.equals(a.refId, ref)).findFirst().orElse(null);
            paidLater(out, ask != null ? ask
                    : new Ask(kind == null ? LOAN : kind, ref, t.getDescription(), null, BigDecimal.ZERO, false), t);
        }

        for (int i = 0; i < owed.size(); i++) {
            Owed o = owed.get(i);
            Ask a = asks.get(i);
            // `left` already excludes repayments recorded ahead (paying bumps paidAmount at once),
            // so only what this month still owes after them comes off it for the months after.
            BigDecimal remaining = o.left();
            if (a.owed.signum() > 0) {
                owing(out, today, a);
                remaining = remaining.subtract(a.owed);
            }
            for (YearMonth ym = current.plusMonths(1); remaining.signum() > 0; ym = ym.plusMonths(1)) {
                LocalDate date = dayIn(ym, o.day());
                if (date.isAfter(to)) break;
                if (!OverviewService.hasStartedBy(o.start(), ym)) continue;
                BigDecimal ask = o.ask(remaining);
                if (ask.signum() <= 0) break;
                out.add(item(date, o.kind(), o.id(), o.name(), ask, false));
                asksByMonth.merge(ym, ask, BigDecimal::add);
                remaining = remaining.subtract(ask);
            }
        }
    }

    // ── Pace ──────────────────────────────────────────────────────────────────

    record Pace(BigDecimal perDay, LocalDate from, LocalDate to) {}

    /** Everyday spending per day over the last 30 days, never reaching back before tracking started. */
    private Pace pace(LocalDate today) {
        LocalDate from = today.minusDays(PACE_WINDOW_DAYS - 1);
        Settings s = settingsService.getOrCreate();
        LocalDate tracking = s == null ? null : s.getAllocationTrackingStartMonth();
        if (tracking != null && tracking.withDayOfMonth(1).isAfter(from)) from = tracking.withDayOfMonth(1);
        long days = daysFrom(from, today);
        if (days < PACE_MIN_DAYS) return null;

        BigDecimal spent = BigDecimal.ZERO;
        List<Transaction> window = transactionRepository.findByTransactionDateBetween(from, today);
        if (window != null) {
            for (Transaction t : window) {
                if (!isUzs(t.getCurrency()) || t.getAmount() == null) continue;
                if (isEverydaySpend(t)) spent = spent.add(t.getAmount());
                else if (isSurplusFound(t)) spent = spent.subtract(t.getAmount());
            }
        }
        BigDecimal perDay = clampZero(spent).divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_UP);
        return new Pace(perDay, from, today);
    }

    /**
     * Money that left a wallet for living: an expense that is not a transfer between the owner's
     * own wallets, not a bill paid through Pay (linked by {@code monthlyPaymentId}), and not a bank
     * installment, a loan or debt repayment, money lent, a donation, an emergency contribution, an
     * investment or a stock purchase — those are already must-pays or savings. A wallet check-in's
     * or month close's EVERYDAY_SPENDING row counts: it is real spending nobody itemised.
     */
    static boolean isEverydaySpend(Transaction t) {
        if (t.getType() != TransactionType.EXPENSE) return false;
        if (t.getTransferPairId() != null || t.getMonthlyPaymentId() != null) return false;
        TransactionSubType st = t.getSubType();
        return st == null || st == TransactionSubType.REGULAR_EXPENSE || st == TransactionSubType.EVERYDAY_SPENDING;
    }

    /**
     * A check-in that found MORE money than computed books the difference as EVERYDAY_SPENDING
     * income — the same correction in the other direction (typically a mistyped balance put
     * right), so it nets against the spending, as the month's everyday figure already does.
     */
    static boolean isSurplusFound(Transaction t) {
        return t.getType() == TransactionType.INCOME && t.getSubType() == TransactionSubType.EVERYDAY_SPENDING;
    }

    /** Money put into an allocation bucket (not a transfer between the owner's own wallets). */
    static boolean isSaving(Transaction t) {
        return t.getType() == TransactionType.EXPENSE && t.getTransferPairId() == null
                && t.getSubType() != null && SAVING_SUB_TYPES.contains(t.getSubType());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Sets {@code amount} aside on {@code on}, but no more than {@code cap} allows (null: no cap).
     * Returns what the cap has left for the next reservation.
     */
    private static BigDecimal reserve(NavigableMap<LocalDate, BigDecimal> savings, LocalDate on,
                                      BigDecimal amount, BigDecimal cap) {
        BigDecimal take = cap == null ? amount : amount.min(clampZero(cap));
        if (take.signum() > 0) savings.merge(on, take, BigDecimal::add);
        return cap == null ? null : cap.subtract(take);
    }

    private static Breakdown breakdown(BigDecimal have, List<IncomePart> incomes, List<Upcoming> items,
                                       NavigableMap<LocalDate, BigDecimal> savings, LocalDate today, LocalDate to) {
        BigDecimal in = incomes.stream().filter(i -> !i.getDate().isAfter(to))
                .map(IncomePart::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal out = items.stream().filter(u -> !u.getDate().isAfter(to))
                .map(Upcoming::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal kept = savings.headMap(to, true).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return Breakdown.builder()
                .have(have).comingIn(in).goingOut(out).savings(kept)
                .net(have.add(in).subtract(out).subtract(kept))
                .days((int) daysFrom(today, to))
                .build();
    }

    /** A must-pay for this month: on its day, or today and overdue when that day has passed. */
    private static Upcoming due(LocalDate today, LocalDate date, String kind, Long refId, String name, BigDecimal amount) {
        boolean overdue = date.isBefore(today);
        return item(overdue ? today : date, kind, refId, name, amount, overdue);
    }

    private static Upcoming item(LocalDate date, String kind, Long refId, String name, BigDecimal amount, boolean overdue) {
        return Upcoming.builder().date(date).kind(kind).refId(refId).name(name == null ? "" : name)
                .amount(amount).overdue(overdue).build();
    }

    /** {@code day} in {@code ym}, clamped to the month's length (the 31st is the 30th in November). */
    static LocalDate dayIn(YearMonth ym, int day) {
        return ym.atDay(Math.min(Math.max(day, 1), ym.lengthOfMonth()));
    }

    /** Days from {@code from} to {@code to}, both included. */
    private static long daysFrom(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    private static int installmentDay(BankLoan b) {
        return b.getTakenDate() == null ? 1 : b.getTakenDate().getDayOfMonth();
    }

    /** The installment's day in {@code ym}, never after the loan's end date. */
    private static LocalDate installmentDate(BankLoan b, YearMonth ym) {
        LocalDate date = dayIn(ym, installmentDay(b));
        return b.getEndDate() != null && date.isAfter(b.getEndDate()) ? b.getEndDate() : date;
    }

    private static int startDay(LocalDate paymentStartDate) {
        return paymentStartDate == null ? 1 : paymentStartDate.getDayOfMonth();
    }

    private static String bankName(BankLoan b) {
        String bank = b.getBankName() == null ? "" : b.getBankName().trim();
        String loan = b.getLoanName() == null ? "" : b.getLoanName().trim();
        if (bank.isEmpty()) return loan;
        return loan.isEmpty() ? bank : bank + " · " + loan;
    }

    /** A bonus is income in a bonus-flagged category or under a flagged parent — the Plan's test. */
    private static boolean isBonus(Transaction t) {
        Category c = t.getCategory();
        if (c == null) return false;
        return Boolean.TRUE.equals(c.getBonusIncome())
                || (c.getParent() != null && Boolean.TRUE.equals(c.getParent().getBonusIncome()));
    }

    /** UZS is the only reporting currency; a legacy row with no currency is taken as UZS. */
    private static boolean isUzs(Currency c) {
        return c == null || c == Currency.UZS;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal clampZero(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }
}
