package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.response.AdvisorResponse;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Bill;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Daily;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Owed;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.SavingsRow;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.SetAside;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Suggestion;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Wallet;
import uz.tracker.trackerproject.dto.response.MonthClosePreviewResponse.WalletLine;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation;
import uz.tracker.trackerproject.dto.response.WalletCheckInStatusResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.EmergencyRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The advisor: one answer to "how am I doing this month and what should I do next", built from
 * the figures the Plan, the wallets and the finance records already compute. It records nothing
 * and decides nothing on the owner's behalf — it reads, adds up, and suggests.
 *
 * <p>The owner asked for an advisor rather than a bookkeeper: tell me what I have, what is coming,
 * what I need to set aside, and nudge me towards goals — with as little input as possible. So the
 * salary is never asked about (it is recorded when it arrives and simply stops being "coming"),
 * and the only thing the advisor ever asks the owner to type is a wallet's real balance.
 *
 * <p>The month-scoped figures ({@code free} and the lists before it) are kept as they were for the
 * clients that read them. The per-day answer that looks past the month's end — through the next
 * salary to the one after — is {@link DailyAdviceService}'s, and it alone decides whether any money
 * is spare. Its warnings (short even spending nothing, or running out at the owner's own pace) live
 * in {@code daily} only: the web shows them on Home, and the Telegram bot — kept unchanged by the
 * owner's choice — never sees a sentence it has no translation for.
 */
@Service
@RequiredArgsConstructor
public class AdvisorService {

    /**
     * How recent the last wallet reconciliation must be before the advisor calls money "spare".
     * Everyday spending is not recorded — a check-in finds it — so an older balance is too high
     * by however much was spent since, and advice to invest it would be advice to invest money
     * that is already gone. Two check-in intervals, so one skipped check-in — in a month's first
     * days the close is suggested instead — does not silence the idea.
     */
    static final int FRESH_BALANCE_DAYS = 2 * WalletCheckInService.INTERVAL_DAYS;
    /** Below this, spare money is not worth a message. */
    static final BigDecimal EXTRA_MIN = new BigDecimal("200000");
    /** Suggested amounts are rounded down to this, so they read like something a person would pick. */
    private static final BigDecimal ROUND_TO = new BigDecimal("10000");
    /** A month-end close is only suggested in the new month's first days, while its balances are still known. */
    static final int CLOSE_WINDOW_DAYS = 5;
    /** "10 October" — for the English fallback sentences only. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);

    private final OverviewService overviewService;
    private final WalletCheckInService walletCheckInService;
    private final MonthCloseService monthCloseService;
    private final TransactionRepository transactionRepository;
    private final LoanGivenRepository loanGivenRepository;
    private final InvestmentRepository investmentRepository;
    private final EmergencyRepository emergencyRepository;
    private final DailyAdviceService dailyAdviceService;

    /** In the month's first this-many days a salary recorded may still be last month's. */
    static final int SALARY_MONTH_HINT_DAYS = 10;

    @Transactional(readOnly = true)
    public AdvisorResponse advise(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("date is required (YYYY-MM-DD)");
        YearMonth month = YearMonth.from(date);
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        // ── You have ──
        WalletCheckInStatusResponse check = walletCheckInService.status(date);
        List<Wallet> wallets = new ArrayList<>();
        BigDecimal have = BigDecimal.ZERO;
        List<WalletLine> computed = check.getWallets() == null ? List.of() : check.getWallets();
        for (WalletLine w : computed) {
            // UZS is the only reporting currency; a dormant foreign cash pot never enters a total.
            if (w.getCurrency() != null && w.getCurrency() != Currency.UZS) continue;
            BigDecimal balance = nz(w.getComputedBalance());
            wallets.add(Wallet.builder()
                    .type(w.getWalletType()).cardId(w.getCardId()).label(w.getLabel())
                    .balance(balance).build());
            have = have.add(balance);
        }

        // ── The month's plan (bills and set-asides even while the rent is unpaid) ──
        // The owner's own today: the month's salary counts up to it in the allocation base.
        OverviewTierResponse tier = overviewService.getTierIgnoringSubscriptions(month, Currency.UZS, date);
        boolean missingIncome = tier.isMissingStableIncome();
        TierAllocation allocation = tier.getAllocation();

        // ── Coming in ──
        BigDecimal expected = missingIncome ? BigDecimal.ZERO : nz(tier.getIncome());
        BigDecimal bonus = nz(tier.getBonusIncome());
        // Regular income minus the bonus: a bonus is booked as regular income in a bonus-flagged
        // category, and it is extra money, not the salary arriving.
        // Counted in its accounting month: a row marked as another month's salary leaves this one,
        // and one marked as this month's salary counts whatever day it arrived (Transaction.salaryMonth).
        BigDecimal regular = nz(transactionRepository.sumBySubTypeCurrencyDateRange(
                TransactionSubType.REGULAR_INCOME, Currency.UZS, start, end));
        List<Transaction> datedHere = transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                TransactionSubType.REGULAR_INCOME, start, end);
        for (Transaction t : datedHere == null ? List.<Transaction>of() : datedHere) {
            if (t.getSalaryMonth() != null && !t.getSalaryMonth().equals(start) && countsAsRegularUzs(t)) {
                regular = regular.subtract(t.getAmount());
            }
        }
        List<Transaction> markedHere = transactionRepository.findBySalaryMonth(start);
        for (Transaction t : markedHere == null ? List.<Transaction>of() : markedHere) {
            LocalDate on = t.getTransactionDate();
            if (t.getSubType() == TransactionSubType.REGULAR_INCOME && countsAsRegularUzs(t)
                    && on != null && (on.isBefore(start) || on.isAfter(end))) {
                regular = regular.add(t.getAmount());
            }
        }
        BigDecimal received = clampZero(regular.subtract(bonus));
        BigDecimal coming = clampZero(expected.subtract(received));

        List<Owed> owed = new ArrayList<>();
        BigDecimal owedTotal = BigDecimal.ZERO;
        for (LoanGiven l : loanGivenRepository.findAll()) {
            BigDecimal out = stillOwedToOwner(l);
            if (out.signum() <= 0) continue;
            owed.add(Owed.builder().id(l.getId()).name(l.getDebtorName())
                    .amount(out).expectedOn(l.getExpectedReturnDate()).build());
            owedTotal = owedTotal.add(out);
        }
        owed.sort(Comparator.comparing(Owed::getExpectedOn, Comparator.nullsLast(Comparator.naturalOrder())));

        // ── Still this month ──
        List<Bill> bills = new ArrayList<>();
        if (tier.getPendingSubscriptions() != null) {
            for (OverviewTierResponse.PendingSubscription p : tier.getPendingSubscriptions()) {
                BigDecimal left = clampZero(nz(p.getAmount()).subtract(nz(p.getPaid())));
                if (left.signum() <= 0) continue;
                bills.add(Bill.builder().kind("SUBSCRIPTION").refId(p.getId()).name(p.getName())
                        .amount(left).paid(nz(p.getPaid())).target(nz(p.getAmount())).build());
            }
        }
        if (allocation != null && allocation.getActions() != null) {
            for (TierAllocation.ActionItem a : allocation.getActions()) {
                if (a.getAction() == null || a.getTarget() == null) continue;
                BigDecimal left = clampZero(a.getTarget().subtract(nz(a.getPaid())));
                if (left.signum() <= 0) continue;
                bills.add(Bill.builder().kind(billKind(a)).amount(left)
                        .paid(nz(a.getPaid())).target(a.getTarget()).build());
            }
        }
        BigDecimal billsLeft = bills.stream().map(Bill::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // What earlier months left unpaid, per bucket (the owner's carry rule: an overpayment never
        // carries). It is owed on top of this month's target — even by a bucket this month's rule
        // does not ask for.
        Map<String, BigDecimal> carried = missingIncome ? Map.of() : overviewService.carriedInto(month);
        List<SetAside> setAside = new ArrayList<>();
        for (BucketDue d : bucketDues(allocation, carried)) {
            if (d.remaining().signum() <= 0) continue;
            setAside.add(SetAside.builder().bucket(d.bucket()).percent(d.percent()).target(d.target())
                    .carried(d.carried()).paid(d.paid()).remaining(d.remaining()).build());
        }
        BigDecimal setAsideLeft = setAside.stream().map(SetAside::getRemaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean afterBills = tier.isSubscriptionsPending()
                || (allocation != null && allocation.isAllocationLocked());

        // ── Free ──
        BigDecimal free = missingIncome ? null : have.add(coming).subtract(billsLeft).subtract(setAsideLeft);

        // ── Savings goals with a monthly payment: outside the plan's percentages, set aside all the same ──
        List<Investment> holdings = investmentRepository.findAll();
        List<GoalMonth> goalMonths = missingIncome ? List.of() : goalMonths(holdings, date);

        // ── Per day: past this month, up to the salary after next ──
        DailyAdviceService.Result perDay = missingIncome ? null : dailyAdviceService.compute(
                new DailyAdviceService.Inputs(date, have, expected, coming, setAsideLeft,
                        goalMonths.stream().map(GoalMonth::plan).toList()));
        Daily daily = perDay == null ? null : perDay.daily();

        // ── What to do next ──
        boolean hasEmergencyFund = emergencyRepository.count() > 0
                || holdings.stream().anyMatch(i -> Boolean.TRUE.equals(i.getEmergencyFund()));
        List<Suggestion> suggestions = new ArrayList<>();

        if (missingIncome) {
            suggestions.add(suggestion("advisor.s.setIncome", Map.of(), "DO", "SET_INCOME",
                    "Set your monthly income so I can plan your month."));
        }
        for (Bill b : bills) suggestions.add(billSuggestion(b));

        Suggestion close = closeMonthSuggestion(date);
        if (close != null) {
            suggestions.add(close);
        } else if (check.isDue()) {
            // A close reconciles every wallet, so while one is on offer a check-in would ask twice.
            Integer days = check.getDaysSinceLastReconciled();
            suggestions.add(days == null
                    ? suggestion("advisor.s.checkWalletsFirst", Map.of(), "DO", "CHECK_IN",
                            "Tell me what's in each wallet, so my numbers match yours.")
                    : suggestion("advisor.s.checkWallets", Map.of("days", String.valueOf(days)), "DO", "CHECK_IN",
                            "Check your wallets — the last check was " + days + " days ago."));
        }

        if (!missingIncome) {
            if (!afterBills) {
                for (SetAside a : setAside) {
                    boolean starting = "EMERGENCY".equals(a.getBucket()) && !hasEmergencyFund;
                    suggestions.add(Suggestion.builder()
                            .code(starting ? "advisor.s.startEmergency" : "advisor.s.setAside")
                            .params(Map.of("bucket", a.getBucket()))
                            .text(starting
                                    ? "Start your emergency fund: set aside " + fmt(a.getRemaining()) + " this month."
                                    : "Set aside " + fmt(a.getRemaining()) + " for " + a.getBucket().toLowerCase() + ".")
                            .kind("DO").action("SET_ASIDE").bucket(a.getBucket()).amount(a.getRemaining())
                            .build());
                }
            }
            // "Short" is now daily.shortBy: it sees past this month's end, where the rent after
            // next payday lives, so the month-only check it replaced is gone.

            List<Investment> goals = holdings.stream()
                    .filter(i -> Boolean.TRUE.equals(i.getSavingsGoal()))
                    .toList();
            if (goals.isEmpty()) {
                suggestions.add(suggestion("advisor.s.addGoal", Map.of(), "IDEA", "ADD_GOAL",
                        "Saving for something — a home, a car, a trip? Add it as a goal and I'll help you get there."));
            }

            Suggestion extra = extraSuggestion(perDay, coming, afterBills, check, goals, hasEmergencyFund);
            if (extra != null) suggestions.add(extra);
        }

        return AdvisorResponse.builder()
                .date(date)
                .month(month.toString())
                .suggestedSalaryMonth(suggestedSalaryMonth(date, expected))
                .currency(Currency.UZS)
                .missingStableIncome(missingIncome)
                .have(have)
                .wallets(wallets)
                .balanceCheckedOn(check.getLastReconciledOn())
                .balanceCheckedDaysAgo(check.getDaysSinceLastReconciled())
                .salaryExpected(expected)
                .salaryReceived(received)
                .salaryComing(coming)
                .bonusReceived(bonus)
                .owedToYou(owed)
                .owedToYouTotal(owedTotal)
                .bills(bills)
                .billsLeft(billsLeft)
                .setAside(setAside)
                .setAsideLeft(setAsideLeft)
                .setAsideAfterBills(afterBills)
                .free(free)
                .suggestions(suggestions)
                .daily(daily)
                .savingsThisMonth(missingIncome ? List.of() : savingsRows(bucketDues(allocation, carried), goalMonths, month))
                .build();
    }

    /**
     * Every bucket with a target this month, met or not — the {@link SetAside} figures without the
     * "still to do" filter, so a screen can show a bucket as done rather than as missing — then each
     * savings goal's monthly payment, once the goal's payment has started (its start month).
     */
    static List<SavingsRow> savingsRows(List<BucketDue> buckets, List<GoalMonth> goals, YearMonth month) {
        List<SavingsRow> rows = new ArrayList<>();
        for (BucketDue d : buckets) {
            if (d.target().signum() <= 0 && d.carried().signum() <= 0) continue;
            rows.add(SavingsRow.builder().bucket(d.bucket()).percent(d.percent()).target(d.target())
                    .carried(d.carried()).paid(d.paid()).remaining(d.remaining()).build());
        }
        for (GoalMonth g : goals) {
            if (g.startedBy(month)) rows.add(g.row());
        }
        return rows;
    }

    /**
     * {@code savingsThisMonth} on its own, built from the same pieces {@link #advise} builds it from:
     * the Plan's allocation for the owner's day, what earlier months carried into each bucket, and
     * the savings goals' monthly payments. Empty without a stable income. Package-private: what
     * Analytics calls a month's "asked" is each row's target + carried.
     */
    List<SavingsRow> savingsThisMonth(LocalDate date) {
        YearMonth month = YearMonth.from(date);
        OverviewTierResponse tier = overviewService.getTierIgnoringSubscriptions(month, Currency.UZS, date);
        if (tier.isMissingStableIncome()) return List.of();
        return savingsRows(bucketDues(tier.getAllocation(), overviewService.carriedInto(month)),
                goalMonths(investmentRepository.findAll(), date), month);
    }

    /**
     * What a borrower still owes the owner on one loan given: UZS, not settled — zero otherwise.
     * Package-private: Analytics' "owed to you" is the same sum as {@code owedToYouTotal}.
     */
    static BigDecimal stillOwedToOwner(LoanGiven l) {
        if (l.getStatus() == RecordStatus.PAID) return BigDecimal.ZERO;
        if (l.getCurrency() != null && l.getCurrency() != Currency.UZS) return BigDecimal.ZERO;
        return clampZero(nz(l.getTotalAmount()).subtract(nz(l.getReceivedAmount())));
    }

    /**
     * One bucket this month: its rule's target, what earlier months carried into it, what went in, what is left.
     * Package-private with {@link #bucketDues}: Analytics' "asked" is this month's target + carried.
     */
    record BucketDue(String bucket, BigDecimal percent, BigDecimal target, BigDecimal carried,
                             BigDecimal paid, BigDecimal remaining) {}

    /**
     * Each bucket of the Plan's allocation with what earlier months carried into it:
     * remaining = max(0, target + carried − paid). A bucket this month's rule does not ask for has a
     * target (and percent) of 0 — and still owes what it carries.
     */
    static List<BucketDue> bucketDues(TierAllocation allocation, Map<String, BigDecimal> carried) {
        List<BucketDue> out = new ArrayList<>();
        if (allocation == null || allocation.getLines() == null) return out;
        for (TierAllocation.AllocationLine line : allocation.getLines()) {
            BigDecimal carry = nz(carried.get(line.getBucket()));
            boolean asked = line.isRecommended();
            BigDecimal target = asked ? nz(line.getMinAmount()) : BigDecimal.ZERO;
            BigDecimal percent = asked ? line.getMinPercent() : BigDecimal.ZERO;
            BigDecimal paid = nz(line.getPaidAmount());
            out.add(new BucketDue(line.getBucket(), percent, target, carry, paid,
                    clampZero(target.add(carry).subtract(paid))));
        }
        return out;
    }

    /**
     * One savings goal's month: its monthly payment, what was put into it this month by today, what
     * is still missing to reach its target (null when it has none), and the month its payment starts
     * (null: always started).
     */
    record GoalMonth(Investment goal, BigDecimal monthly, BigDecimal paid, BigDecimal toTarget,
                     YearMonth startMonth) {
        boolean startedBy(YearMonth month) {
            return startMonth == null || !startMonth.isAfter(month);
        }

        /** The month's payment, but never more than finishes the goal. */
        SavingsRow row() {
            BigDecimal target = toTarget == null ? monthly : monthly.min(paid.add(toTarget));
            return SavingsRow.builder().bucket("GOAL").refId(goal.getId()).name(goal.getName())
                    .target(target).paid(paid).remaining(clampZero(target.subtract(paid))).build();
        }

        DailyAdviceService.Goal plan() {
            return new DailyAdviceService.Goal(goal.getId(), monthly, paid, toTarget, startMonth);
        }
    }

    /**
     * The savings goals with a monthly payment that have not reached their target (or have none) —
     * those whose payment starts in a later month too: the daily figure sets their payment aside
     * from that month's payday, while this month's rows list only the started ones. "Paid" is what
     * was put into the goal this month up to and including {@code date} — a contribution recorded
     * for a later day has not left the wallets yet.
     */
    List<GoalMonth> goalMonths(List<Investment> holdings, LocalDate date) {
        LocalDate start = YearMonth.from(date).atDay(1);
        List<GoalMonth> goals = new ArrayList<>();
        for (Investment g : holdings) {
            if (!Boolean.TRUE.equals(g.getSavingsGoal())) continue;
            if (g.getCurrency() != null && g.getCurrency() != Currency.UZS) continue;
            BigDecimal monthly = nz(g.getMonthlyContribution());
            if (monthly.signum() <= 0 || !belowTarget(g)) continue;
            BigDecimal paid = BigDecimal.ZERO;
            for (Transaction t : transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(g.getId())) {
                if (t.getType() != TransactionType.EXPENSE || t.getAmount() == null) continue;
                if (t.getTransactionDate().isBefore(start) || t.getTransactionDate().isAfter(date)) continue;
                paid = paid.add(t.getAmount());
            }
            BigDecimal toTarget = g.getTargetAmount() == null || g.getTargetAmount().signum() <= 0 ? null
                    : clampZero(g.getTargetAmount().subtract(value(g)));
            goals.add(new GoalMonth(g, monthly, paid, toTarget, g.paymentStartMonth()));
        }
        return goals;
    }

    private static boolean countsAsRegularUzs(Transaction t) {
        return t.getType() == TransactionType.INCOME && t.getAmount() != null
                && (t.getCurrency() == null || t.getCurrency() == Currency.UZS);
    }

    /**
     * Which month's salary a salary recorded today most likely is: the previous month's in the first
     * ten days while that month's salary has not reached half the stable income (September's paid
     * on 3 October), else this month's.
     */
    private String suggestedSalaryMonth(LocalDate date, BigDecimal stable) {
        YearMonth month = YearMonth.from(date);
        if (date.getDayOfMonth() <= SALARY_MONTH_HINT_DAYS && stable.signum() > 0) {
            YearMonth prev = month.minusMonths(1);
            BigDecimal prevSalary = nz(overviewService.salaryReceivedUzs(prev, date));
            if (prevSalary.compareTo(stable.multiply(new BigDecimal("0.5"))) < 0) return prev.toString();
        }
        return month.toString();
    }

    /** The Plan's action item as a bill kind: the bank installment, the loan plan, or the ASAP pay-back. */
    private static String billKind(TierAllocation.ActionItem a) {
        if ("PAY_BANK".equals(a.getAction())) return "BANK";
        if ("page.plan.action.setAside".equals(a.getCode())) return "LOAN_PLAN";
        return "DEBTS";
    }

    private static Suggestion billSuggestion(Bill b) {
        return switch (b.getKind()) {
            case "SUBSCRIPTION" -> Suggestion.builder()
                    .code("advisor.s.paySubscription")
                    .params(Map.of("name", b.getName() == null ? "" : b.getName()))
                    .text(b.getName() + ": " + fmt(b.getAmount()) + " still to pay this month.")
                    .kind("DO").action("PAY_SUBSCRIPTION").refId(b.getRefId()).amount(b.getAmount())
                    .build();
            case "BANK" -> Suggestion.builder()
                    .code("advisor.s.payBank").params(Map.of())
                    .text("Bank loan: " + fmt(b.getAmount()) + " still to pay this month.")
                    .kind("DO").action("PAY_BANK").amount(b.getAmount())
                    .build();
            case "LOAN_PLAN" -> Suggestion.builder()
                    .code("advisor.s.payLoanPlan").params(Map.of())
                    .text("Your loan repayment plan: " + fmt(b.getAmount()) + " still to pay this month.")
                    .kind("DO").action("PAY_DEBT").amount(b.getAmount())
                    .build();
            default -> Suggestion.builder()
                    .code("advisor.s.payDebts").params(Map.of())
                    .text("Debts: " + fmt(b.getAmount()) + " still to pay back this month.")
                    .kind("DO").action("PAY_DEBT").amount(b.getAmount())
                    .build();
        };
    }

    /**
     * "Close last month" — only in the new month's first days, and only when last month is the
     * next one in line. Later than that nobody remembers what each wallet held on the last day,
     * and a close that has to be guessed is worse than none: the wallet check-ins keep the
     * balances right either way.
     */
    private Suggestion closeMonthSuggestion(LocalDate date) {
        if (date.getDayOfMonth() > CLOSE_WINDOW_DAYS) return null;
        YearMonth prev = YearMonth.from(date).minusMonths(1);
        YearMonth latest = monthCloseService.latestClosedMonth();
        if (latest != null && !latest.plusMonths(1).equals(prev)) return null;
        if (latest == null && !transactionRepository.existsByTransactionDateLessThanEqual(prev.atEndOfMonth())) {
            return null;
        }
        return suggestion("advisor.s.closeMonth", Map.of("month", prev.toString()), "DO", "CLOSE_MONTH",
                prev + " is over — tell me what each wallet held at its end to close it.");
    }

    /**
     * Money beyond what the owner will really need, and where to put half of it.
     *
     * <p>"Really need" is measured, not assumed: the spare money is the LEAST the per-day walk ever
     * has in hand, on any day to the end of its horizon (the day before the salary after next),
     * once every bill, loan payment and saving due by then is met AND the owner keeps spending at
     * their own recorded pace — {@code min over d of net(d) − paceDaily × days(d)}. Every day, not
     * just the last: money that carries the owner to payday is not spare because the next salary
     * refills the account afterwards. (Measured only on the last day, a walk short by 5.1M before
     * payday still offered to invest 1.2M — more than the wallets held.) The old month-scoped
     * estimate took a month's living costs to be whatever the plan left over (about 1.1M) while the
     * owner spent over 12M.
     *
     * <p>Offered only with a known pace, never while the walk says short or running out, with at
     * least {@link #EXTRA_MIN} to spare, and still only once the bills are paid, the salary is in
     * and the wallets were checked recently — a stale balance overstates both the money and, with
     * spending unrecorded since, understates the pace. Half, because the rest is the owner's cushion
     * to decide about.
     */
    private Suggestion extraSuggestion(DailyAdviceService.Result perDay, BigDecimal coming, boolean afterBills,
                                       WalletCheckInStatusResponse check, List<Investment> goals,
                                       boolean hasEmergencyFund) {
        if (perDay == null || perDay.daily() == null || perDay.surplus() == null) return null;
        if (perDay.daily().getShortBy() != null || perDay.daily().getRunsOutOn() != null) return null;
        if (afterBills || coming.signum() > 0) return null;
        Integer days = check.getDaysSinceLastReconciled();
        if (days == null || days > FRESH_BALANCE_DAYS) return null;

        BigDecimal spare = perDay.surplus();
        if (spare.compareTo(EXTRA_MIN) < 0) return null;
        BigDecimal amount = spare.divide(BigDecimal.valueOf(2), 0, RoundingMode.DOWN)
                .divide(ROUND_TO, 0, RoundingMode.DOWN).multiply(ROUND_TO);
        if (amount.signum() <= 0) return null;
        String horizon = "until " + DAY.format(perDay.daily().getUntil()) + " at your usual pace";

        Investment goal = goals.stream().filter(AdvisorService::belowTarget).findFirst().orElse(null);
        if (goal != null) {
            return Suggestion.builder()
                    .code("advisor.s.extraToGoal").params(Map.of("name", goal.getName() == null ? "" : goal.getName()))
                    .text("You have about " + fmt(spare) + " more than you need " + horizon + ". Put "
                            + fmt(amount) + " towards " + goal.getName() + "?")
                    .kind("IDEA").action("SET_ASIDE").bucket("SAVINGS").refId(goal.getId()).amount(amount)
                    .build();
        }
        String bucket = hasEmergencyFund ? "INVESTMENTS" : "EMERGENCY";
        return Suggestion.builder()
                .code(hasEmergencyFund ? "advisor.s.extraToInvestments" : "advisor.s.extraToEmergency")
                .params(Map.of())
                .text("You have about " + fmt(spare) + " more than you need " + horizon + ". Put " + fmt(amount)
                        + (hasEmergencyFund ? " into investments?" : " into an emergency fund?"))
                .kind("IDEA").action("SET_ASIDE").bucket(bucket).amount(amount)
                .build();
    }

    /** A goal with no target, or one not reached yet (current value, else what was put in). */
    private static boolean belowTarget(Investment goal) {
        if (goal.getTargetAmount() == null || goal.getTargetAmount().signum() <= 0) return true;
        return value(goal).compareTo(goal.getTargetAmount()) < 0;
    }

    /** What a holding is worth: its current value, else what was put in. */
    static BigDecimal value(Investment holding) {
        return holding.getCurrentValue() != null ? holding.getCurrentValue() : nz(holding.getInvestedAmount());
    }

    private static Suggestion suggestion(String code, Map<String, String> params, String kind,
                                         String action, String text) {
        return Suggestion.builder().code(code).params(params).text(text).kind(kind).action(action).build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal clampZero(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    /** "1 250 000 UZS" — only for the English fallback sentence; clients format {@code amount} themselves. */
    private static String fmt(BigDecimal n) {
        return plain(n).replaceAll("(\\d)(?=(\\d{3})+$)", "$1 ") + " UZS";
    }

    /** "1250000" — whole som, no grouping: how an amount travels inside {@code params}. */
    private static String plain(BigDecimal n) {
        return n.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
