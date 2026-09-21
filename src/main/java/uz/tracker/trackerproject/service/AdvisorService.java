package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.response.AdvisorResponse;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Bill;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Owed;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.SetAside;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Suggestion;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Wallet;
import uz.tracker.trackerproject.dto.response.MonthClosePreviewResponse.WalletLine;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation;
import uz.tracker.trackerproject.dto.response.WalletCheckInStatusResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.repository.EmergencyRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 */
@Service
@RequiredArgsConstructor
public class AdvisorService {

    /**
     * How recent the last wallet reconciliation must be before the advisor calls money "spare".
     * Everyday spending is not recorded — a check-in finds it — so an older balance is too high
     * by however much was spent since, and advice to invest it would be advice to invest money
     * that is already gone. Two check-in intervals: the last days of a month allow no check-in.
     */
    static final int FRESH_BALANCE_DAYS = 2 * WalletCheckInService.INTERVAL_DAYS;
    /** Below this, spare money is not worth a message. */
    static final BigDecimal EXTRA_MIN = new BigDecimal("200000");
    /** Suggested amounts are rounded down to this, so they read like something a person would pick. */
    private static final BigDecimal ROUND_TO = new BigDecimal("10000");
    /** A month-end close is only suggested in the new month's first days, while its balances are still known. */
    static final int CLOSE_WINDOW_DAYS = 5;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OverviewService overviewService;
    private final WalletCheckInService walletCheckInService;
    private final MonthCloseService monthCloseService;
    private final TransactionRepository transactionRepository;
    private final LoanGivenRepository loanGivenRepository;
    private final InvestmentRepository investmentRepository;
    private final EmergencyRepository emergencyRepository;

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
        OverviewTierResponse tier = overviewService.getTierIgnoringSubscriptions(month, Currency.UZS);
        boolean missingIncome = tier.isMissingStableIncome();
        TierAllocation allocation = tier.getAllocation();

        // ── Coming in ──
        BigDecimal expected = missingIncome ? BigDecimal.ZERO : nz(tier.getIncome());
        BigDecimal bonus = nz(tier.getBonusIncome());
        // Regular income minus the bonus: a bonus is booked as regular income in a bonus-flagged
        // category, and it is extra money, not the salary arriving.
        BigDecimal regular = nz(transactionRepository.sumBySubTypeCurrencyDateRange(
                TransactionSubType.REGULAR_INCOME, Currency.UZS, start, end));
        BigDecimal received = clampZero(regular.subtract(bonus));
        BigDecimal coming = clampZero(expected.subtract(received));

        List<Owed> owed = new ArrayList<>();
        BigDecimal owedTotal = BigDecimal.ZERO;
        for (LoanGiven l : loanGivenRepository.findAll()) {
            if (l.getStatus() == RecordStatus.PAID) continue;
            if (l.getCurrency() != null && l.getCurrency() != Currency.UZS) continue;
            BigDecimal out = nz(l.getTotalAmount()).subtract(nz(l.getReceivedAmount()));
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

        List<SetAside> setAside = new ArrayList<>();
        BigDecimal pctSum = BigDecimal.ZERO;
        if (allocation != null && allocation.getLines() != null) {
            for (TierAllocation.AllocationLine line : allocation.getLines()) {
                if (!line.isRecommended()) continue;
                pctSum = pctSum.add(nz(line.getMinPercent()));
                BigDecimal remaining = nz(line.getRemainingAmount());
                if (remaining.signum() <= 0) continue;
                setAside.add(SetAside.builder().bucket(line.getBucket()).percent(line.getMinPercent())
                        .target(nz(line.getMinAmount())).paid(nz(line.getPaidAmount()))
                        .remaining(remaining).build());
            }
        }
        BigDecimal setAsideLeft = setAside.stream().map(SetAside::getRemaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean afterBills = tier.isSubscriptionsPending()
                || (allocation != null && allocation.isAllocationLocked());

        // ── Free ──
        BigDecimal free = missingIncome ? null : have.add(coming).subtract(billsLeft).subtract(setAsideLeft);

        // ── What to do next ──
        List<Investment> holdings = investmentRepository.findAll();
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
            if (free.signum() < 0) {
                suggestions.add(Suggestion.builder().code("advisor.s.short").params(Map.of())
                        .text("Heads up: after bills and set-asides you'd be " + fmt(free.negate())
                                + " short this month.")
                        .kind("WARN").amount(free.negate()).build());
            }

            List<Investment> goals = holdings.stream()
                    .filter(i -> Boolean.TRUE.equals(i.getSavingsGoal()))
                    .toList();
            if (goals.isEmpty()) {
                suggestions.add(suggestion("advisor.s.addGoal", Map.of(), "IDEA", "ADD_GOAL",
                        "Saving for something — a home, a car, a trip? Add it as a goal and I'll help you get there."));
            }

            Suggestion extra = extraSuggestion(date, tier, pctSum, free, coming, afterBills, check,
                    goals, hasEmergencyFund);
            if (extra != null) suggestions.add(extra);
        }

        return AdvisorResponse.builder()
                .date(date)
                .month(month.toString())
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
                .build();
    }

    /** The Plan's action item as a bill kind: the bank installment, the loan plan, or the 34% pay-down. */
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
                    .text("Debts: " + fmt(b.getAmount()) + " still to pay this month (34% rule).")
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
     * Money beyond what the rest of the month needs, and where to put half of it.
     *
     * <p>"What the month needs" is the owner's own plan, not a rule of thumb: the living money a
     * normal month leaves — the left balance after bills, debts and the regular set-asides — for
     * the days still to go. Only offered when that estimate can be trusted: the salary is in (money
     * still to come is not money to invest), the bills are paid, and the wallets were checked
     * recently. Half, because the rest is the owner's cushion to decide about.
     */
    private Suggestion extraSuggestion(LocalDate date, OverviewTierResponse tier, BigDecimal pctSum,
                                       BigDecimal free, BigDecimal coming, boolean afterBills,
                                       WalletCheckInStatusResponse check, List<Investment> goals,
                                       boolean hasEmergencyFund) {
        if (afterBills || coming.signum() > 0 || free == null) return null;
        Integer days = check.getDaysSinceLastReconciled();
        if (days == null || days > FRESH_BALANCE_DAYS) return null;

        BigDecimal monthlyBase = clampZero(nz(tier.getAllocationBase()).subtract(nz(tier.getBonusIncome())));
        BigDecimal monthlyLiving = monthlyBase.multiply(HUNDRED.subtract(pctSum)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        int daysInMonth = date.lengthOfMonth();
        int daysLeft = daysInMonth - date.getDayOfMonth() + 1;
        BigDecimal livingLeft = monthlyLiving.multiply(BigDecimal.valueOf(daysLeft))
                .divide(BigDecimal.valueOf(daysInMonth), 2, RoundingMode.HALF_UP);

        BigDecimal spare = free.subtract(livingLeft);
        if (spare.compareTo(EXTRA_MIN) < 0) return null;
        BigDecimal amount = spare.divide(BigDecimal.valueOf(2), 0, RoundingMode.DOWN)
                .divide(ROUND_TO, 0, RoundingMode.DOWN).multiply(ROUND_TO);
        if (amount.signum() <= 0) return null;

        Investment goal = goals.stream().filter(AdvisorService::belowTarget).findFirst().orElse(null);
        if (goal != null) {
            return Suggestion.builder()
                    .code("advisor.s.extraToGoal").params(Map.of("name", goal.getName() == null ? "" : goal.getName()))
                    .text("You have about " + fmt(spare) + " more than this month needs. Put "
                            + fmt(amount) + " towards " + goal.getName() + "?")
                    .kind("IDEA").action("SET_ASIDE").bucket("SAVINGS").refId(goal.getId()).amount(amount)
                    .build();
        }
        String bucket = hasEmergencyFund ? "INVESTMENTS" : "EMERGENCY";
        return Suggestion.builder()
                .code(hasEmergencyFund ? "advisor.s.extraToInvestments" : "advisor.s.extraToEmergency")
                .params(Map.of())
                .text("You have about " + fmt(spare) + " more than this month needs. Put " + fmt(amount)
                        + (hasEmergencyFund ? " into investments?" : " into an emergency fund?"))
                .kind("IDEA").action("SET_ASIDE").bucket(bucket).amount(amount)
                .build();
    }

    /** A goal with no target, or one not reached yet (current value, else what was put in). */
    private static boolean belowTarget(Investment goal) {
        if (goal.getTargetAmount() == null || goal.getTargetAmount().signum() <= 0) return true;
        BigDecimal value = goal.getCurrentValue() != null ? goal.getCurrentValue() : nz(goal.getInvestedAmount());
        return value.compareTo(goal.getTargetAmount()) < 0;
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
        return n.setScale(0, RoundingMode.HALF_UP).toPlainString()
                .replaceAll("(\\d)(?=(\\d{3})+$)", "$1 ") + " UZS";
    }
}
