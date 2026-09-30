package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.SavingsRow;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.BiggestDay;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.BiggestRow;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.BillLine;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Day;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Everyday;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.EverydayCategory;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.EverydayChild;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Flow;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.IncomeLine;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.LoanLine;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.MonthFlow;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Position;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.PositionLoan;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.PositionMonth;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.PreviousDay;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.SavingLine;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.Emergency;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.PositionSnapshot;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RepaymentType;
import uz.tracker.trackerproject.enums.TransactionFlow;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.EmergencyRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Analytics page's one read: where the money came from, where it went, what was saved, and
 * what the owner owns and owes — for a range of months.
 *
 * <p><b>Which rows count.</b> UZS only (currency UZS or null); by {@code transactionDate}, never by
 * {@code salaryMonth} (the basis History and the wallets use); dated on or before the owner's day;
 * a transfer between the owner's own wallets is ignored entirely.
 *
 * <p><b>How a counted row is classified</b> — {@link #classify}, first match wins:
 * <ol>
 *   <li>INCOME, LOAN_RECEIVED → borrowed</li>
 *   <li>INCOME, LOAN_RETURNED_TO_ME → returned</li>
 *   <li>INCOME, INVESTMENT_WITHDRAWAL → from savings</li>
 *   <li>INCOME, EVERYDAY_SPENDING (a wallet check found more than expected) → a correction,
 *       <i>subtracted</i> from the not-itemised everyday spending</li>
 *   <li>any other INCOME → earned: bonus (a bonus-flagged category), else pay (the salary tree),
 *       else other</li>
 *   <li>EXPENSE, LOAN_GIVEN → lent</li>
 *   <li>EXPENSE, DONATION / EMERGENCY_CONTRIBUTION / INVESTMENT / STOCK_PURCHASE → saved, split by
 *       the row's allocation bucket</li>
 *   <li>EXPENSE, BANK_LOAN_PAYMENT / LOAN_REPAYMENT → loan payment</li>
 *   <li>EXPENSE with a monthlyPaymentId (a bill paid through Pay) → bill</li>
 *   <li>EXPENSE, EVERYDAY_SPENDING (a wallet check found less than expected) → everyday, not itemised</li>
 *   <li>any other EXPENSE → everyday, itemised</li>
 * </ol>
 * The decision itself is {@link TransactionFlows#of} — the {@code flow} every
 * {@code TransactionResponse} carries — so History, the bot and this page read one classification;
 * its predicates are the ones Home's pace already uses ({@link DailyAdviceService#isEverydaySpend},
 * {@link DailyAdviceService#isSurplusFound}, {@link DailyAdviceService#isSaving}). Earned income is
 * split by {@link OverviewService#isBonusCategory} and {@link OverviewService#isSalaryCategory} over
 * {@link OverviewService#salaryTree()}, saved money by {@link OverviewService#bucketOf}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    /** The longest range one request may ask for. */
    static final int MAX_MONTHS = 24;
    /** Before any row the owner could have recorded. */
    private static final LocalDate BEGINNING = LocalDate.of(1970, 1, 1);
    static final String UNCATEGORIZED = "Uncategorized";
    static final String BANK_LOAN = "Bank loan";

    static final String PAY = "PAY";
    static final String BONUS = "BONUS";
    static final String OTHER = "OTHER";
    static final String DONATION = "DONATION";
    static final String EMERGENCY = "EMERGENCY";
    static final String INVESTMENTS = "INVESTMENTS";
    static final String GOAL = "GOAL";
    static final String BANK = "BANK";
    static final String LOAN = "LOAN";
    static final String DEBT = "DEBT";

    /** The class a counted row falls into (the list in the class comment, in its order). */
    enum FlowClass {
        BORROWED, RETURNED, FROM_SAVINGS, CORRECTION, EARNED, LENT, SAVED, LOAN_PAYMENT, BILL,
        EVERYDAY_UNITEMISED, EVERYDAY
    }

    private final TransactionRepository transactionRepository;
    private final SettingsService settingsService;
    private final OverviewService overviewService;
    private final AdvisorService advisorService;
    private final MonthCloseService monthCloseService;
    private final InvestmentRepository investmentRepository;
    private final EmergencyRepository emergencyRepository;
    private final LoanTakenRepository loanTakenRepository;
    private final DebtRepository debtRepository;
    private final BankLoanRepository bankLoanRepository;
    private final LoanGivenRepository loanGivenRepository;
    private final MonthlyPaymentRepository monthlyPaymentRepository;
    private final PositionSnapshotService positionSnapshotService;

    // ── Which rows count, and as what ─────────────────────────────────────────

    /** A row Analytics looks at at all: UZS money, dated, and not a transfer. */
    static boolean counts(Transaction t) {
        return t.getAmount() != null && t.getTransactionDate() != null && t.getType() != null
                && (t.getCurrency() == null || t.getCurrency() == Currency.UZS)
                && TransactionFlows.of(t) != TransactionFlow.TRANSFER;
    }

    /**
     * The class of a row that {@link #counts}: its {@link TransactionFlow} — the one every
     * {@code TransactionResponse} carries — with the two things only this page tells apart: a
     * donation (GIVEN) is a saving here too, split off by its bucket, and everyday spending found by
     * a wallet check (sub-type EVERYDAY_SPENDING) is the part nobody itemised.
     */
    static FlowClass classify(Transaction t) {
        return classOf(TransactionFlows.of(t), t);
    }

    private static FlowClass classOf(TransactionFlow flow, Transaction t) {
        return switch (flow) {
            case BORROWED -> FlowClass.BORROWED;
            case RETURNED -> FlowClass.RETURNED;
            case FROM_SAVINGS -> FlowClass.FROM_SAVINGS;
            case CORRECTION -> FlowClass.CORRECTION;
            case EARNED -> FlowClass.EARNED;
            case LENT -> FlowClass.LENT;
            case SAVED, GIVEN -> FlowClass.SAVED;
            case LOAN_PAYMENT -> FlowClass.LOAN_PAYMENT;
            case BILL -> FlowClass.BILL;
            case EVERYDAY -> t.getSubType() == TransactionSubType.EVERYDAY_SPENDING
                    ? FlowClass.EVERYDAY_UNITEMISED : FlowClass.EVERYDAY;
            case TRANSFER -> throw new IllegalArgumentException("A transfer between own wallets is not counted.");
        };
    }

    /** PAY | BONUS | OTHER for a row of earned income, by its own category. */
    static String incomeKind(Category c, Set<Long> salaryTree) {
        if (OverviewService.isBonusCategory(c)) return BONUS;
        return OverviewService.isSalaryCategory(c, salaryTree) ? PAY : OTHER;
    }

    /**
     * DONATION | EMERGENCY | INVESTMENTS | GOAL for a row of saved money, by its allocation bucket.
     * A donation is whatever the row's flow calls GIVEN — never decided a second time here.
     */
    private String savedKind(Transaction t) {
        if (TransactionFlows.of(t) == TransactionFlow.GIVEN) return DONATION;
        String bucket = overviewService.bucketOf(t);
        if (AllocationBucket.EMERGENCY.equals(bucket)) return EMERGENCY;
        if (AllocationBucket.SAVINGS.equals(bucket)) return GOAL;
        return INVESTMENTS; // INVESTMENTS, and the legacy STOCKS bucket
    }

    /** A counted row with its month and class. */
    private record Row(Transaction t, YearMonth month, FlowClass cls) {
        BigDecimal amount() {
            return t.getAmount();
        }

        LocalDate date() {
            return t.getTransactionDate();
        }
    }

    // ── The endpoint ──────────────────────────────────────────────────────────

    /**
     * @param fromArg first month of the range; null = {@code toArg}
     * @param toArg   last month of the range; null = the month of {@code date}
     * @param date    the owner's local day
     */
    @Transactional(readOnly = true)
    public AnalyticsResponse analytics(YearMonth fromArg, YearMonth toArg, LocalDate date) {
        if (date == null) throw new IllegalArgumentException("date is required (YYYY-MM-DD)");
        YearMonth current = YearMonth.from(date);
        YearMonth to = toArg != null ? toArg : current;
        YearMonth from = fromArg != null ? fromArg : to;
        if (to.isAfter(current)) {
            throw new IllegalArgumentException("to can't be after the month of date (" + current + "), got: " + to);
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from can't be after to, got: " + from + " and " + to);
        }
        int span = (int) ChronoUnit.MONTHS.between(from, to) + 1;
        if (span > MAX_MONTHS) {
            throw new IllegalArgumentException("The range can't be longer than " + MAX_MONTHS + " months, got: " + span);
        }
        boolean oneMonth = from.equals(to);
        YearMonth previousFrom = from.minusMonths(span);
        YearMonth previousTo = from.minusMonths(1);

        // ── The rows ──
        List<Transaction> all = transactionRepository.findByTransactionDateBetween(BEGINNING, current.atEndOfMonth());
        List<Row> inRange = new ArrayList<>();
        List<Row> before = new ArrayList<>();
        TreeSet<YearMonth> monthsWithRows = new TreeSet<>();
        int notYet = 0;
        for (Transaction t : all == null ? List.<Transaction>of() : all) {
            if (!counts(t)) continue;
            YearMonth month = YearMonth.from(t.getTransactionDate());
            boolean within = !month.isBefore(from) && !month.isAfter(to);
            if (t.getTransactionDate().isAfter(date)) {
                if (within) notYet++;
                continue;
            }
            monthsWithRows.add(month);
            if (within) inRange.add(new Row(t, month, classify(t)));
            else if (!month.isBefore(previousFrom) && !month.isAfter(previousTo)) before.add(new Row(t, month, classify(t)));
        }
        YearMonth firstMonth = monthsWithRows.isEmpty() ? null : monthsWithRows.first();

        // ── Flow: each month, the range, and the range before it ──
        Set<Long> salaryTree = overviewService.salaryTree();
        Map<YearMonth, MonthFlow> byMonth = new LinkedHashMap<>();
        if (firstMonth != null) {
            for (YearMonth m = from.isBefore(firstMonth) ? firstMonth : from; !m.isAfter(to); m = m.plusMonths(1)) {
                boolean complete = m.isBefore(current);
                byMonth.put(m, new MonthFlow(m.toString(), complete, complete ? m.lengthOfMonth() : date.getDayOfMonth()));
            }
        }
        Details details = new Details(oneMonth ? from : null);
        for (Row r : inRange) {
            String kind = kindOf(r, salaryTree);
            book(byMonth.get(r.month()), r, kind);
            details.add(r, kind);
        }
        Flow totals = new Flow();
        int days = 0;
        for (MonthFlow m : byMonth.values()) {
            m.settle();
            totals.add(m);
            days += m.getDays();
        }
        totals.settle();

        Flow previous = null;
        Map<Long, BigDecimal> previousByCategory = new HashMap<>();
        BigDecimal[] previousByDay = oneMonth ? zeros(previousTo.lengthOfMonth()) : null;
        if (!before.isEmpty()) {
            previous = new Flow();
            for (Row r : before) {
                book(previous, r, kindOf(r, salaryTree));
                if (r.cls() == FlowClass.EVERYDAY) {
                    previousByCategory.merge(idOf(top(r.t().getCategory())), r.amount(), BigDecimal::add);
                }
                if (previousByDay != null) {
                    int d = r.date().getDayOfMonth() - 1;
                    previousByDay[d] = previousByDay[d].add(everydayPart(r));
                }
            }
            previous.settle();
        }

        // ── The parts ──
        Everyday everyday = Everyday.builder()
                .total(totals.getEveryday())
                .days(days)
                .perDay(days == 0 ? null : totals.getEveryday().divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_UP))
                .unitemised(totals.getEverydayUnitemised())
                .categories(details.categories(previous == null ? null : previousByCategory))
                .daily(oneMonth ? details.daily(from, date) : List.of())
                .previousDaily(previous == null || previousByDay == null ? null : cumulative(previousByDay))
                .biggestDays(oneMonth ? details.biggestDays(from) : List.of())
                .biggest(details.biggest())
                .build();

        List<Investment> holdings = uzsHoldings();
        Settings settings = settingsService.getOrCreate();
        BigDecimal stable = settings == null ? null : settings.getMonthlyStableIncome();

        Position position = to.equals(current) ? position(date, holdings) : null;
        if (position != null) remember(position);

        return AnalyticsResponse.builder()
                .currency(Currency.UZS)
                .date(date)
                .from(from.toString())
                .to(to.toString())
                .firstMonth(firstMonth == null ? null : firstMonth.toString())
                .monthsWithData(monthsWithRows.size())
                .stableIncome(stable == null || stable.signum() <= 0 ? null : stable)
                .notYetCount(notYet)
                .totals(totals)
                .previous(previous)
                .months(new ArrayList<>(byMonth.values()))
                .income(details.income())
                .everyday(everyday)
                .bills(details.bills(billNames()))
                .loanPayments(details.loanPayments())
                .savings(savings(totals, details, holdings, oneMonth ? from : null, date))
                .position(position)
                .positionHistory(positionHistory())
                .build();
    }

    /** The kind within a row's class: PAY/BONUS/OTHER for earned, the bucket for saved; null otherwise. */
    private String kindOf(Row r, Set<Long> salaryTree) {
        return switch (r.cls()) {
            case EARNED -> incomeKind(r.t().getCategory(), salaryTree);
            case SAVED -> savedKind(r.t());
            default -> null;
        };
    }

    /** Put one counted row into a flow ({@code kind} from {@link #kindOf}). */
    private static void book(Flow f, Row r, String kind) {
        BigDecimal a = r.amount();
        switch (r.cls()) {
            case BORROWED -> f.addBorrowed(a);
            case RETURNED -> f.addReturned(a);
            case FROM_SAVINGS -> f.addFromSavings(a);
            case CORRECTION -> f.addEverydayUnitemised(a.negate());
            case EARNED -> f.addEarned(a, kind);
            case LENT -> f.addLent(a);
            case SAVED -> f.addSaved(a, kind);
            case LOAN_PAYMENT -> f.addLoanPayment(a);
            case BILL -> f.addBill(a);
            case EVERYDAY_UNITEMISED -> f.addEverydayUnitemised(a);
            case EVERYDAY -> f.addEverydayItemised(a);
        }
        f.counted();
    }

    /** What a row adds to everyday spending: itemised and not itemised add, a correction takes off. */
    private static BigDecimal everydayPart(Row r) {
        return switch (r.cls()) {
            case EVERYDAY, EVERYDAY_UNITEMISED -> r.amount();
            case CORRECTION -> r.amount().negate();
            default -> BigDecimal.ZERO;
        };
    }

    // ── The range's breakdowns ────────────────────────────────────────────────

    private static final class CategorySum {
        private final Category category;
        private BigDecimal amount = BigDecimal.ZERO;
        private int count;
        private final Map<Long, CategorySum> children = new LinkedHashMap<>();

        private CategorySum(Category category) {
            this.category = category;
        }

        private void add(BigDecimal a) {
            amount = amount.add(a);
            count++;
        }
    }

    private static final class BillSum {
        private BigDecimal paid = BigDecimal.ZERO;
        private int count;
        private Transaction latest;
    }

    private static final class LoanSum {
        private final String kind;
        private final Long refId;
        private final String name;
        private final boolean asap;
        private BigDecimal paid = BigDecimal.ZERO;

        private LoanSum(String kind, Long refId, String name, boolean asap) {
            this.kind = kind;
            this.refId = refId;
            this.name = name;
            this.asap = asap;
        }
    }

    private static final class GoalSum {
        private final Long refId;
        private final String name;
        private BigDecimal saved = BigDecimal.ZERO;

        private GoalSum(Long refId, String name) {
            this.refId = refId;
            this.name = name;
        }
    }

    /** Everything the range's rows add up to besides the flow itself. */
    private final class Details {
        /** The month when the range is one month (then the per-day figures are kept); else null. */
        private final YearMonth month;
        private final Map<Long, CategorySum> income = new LinkedHashMap<>();
        private final Map<Long, String> incomeKinds = new HashMap<>();
        private final Map<Long, CategorySum> everyday = new LinkedHashMap<>();
        private final List<Row> itemised = new ArrayList<>();
        private final BigDecimal[] dayItemised;
        private final BigDecimal[] dayUnitemised;
        private final Row[] dayTop;
        private final Map<Long, BillSum> bills = new LinkedHashMap<>();
        private final Map<String, LoanSum> loans = new LinkedHashMap<>();
        private final Map<String, GoalSum> goals = new LinkedHashMap<>();
        private Map<Long, LoanTaken> loansTaken;
        private Map<Long, Debt> debts;
        private List<BankLoan> bankLoans;
        private Map<Long, Investment> holdingsById;
        private Map<Long, Investment> holdingsByFirstRow;

        private Details(YearMonth month) {
            this.month = month;
            int length = month == null ? 0 : month.lengthOfMonth();
            this.dayItemised = zeros(length);
            this.dayUnitemised = zeros(length);
            this.dayTop = new Row[length];
        }

        private void add(Row r, String kind) {
            Transaction t = r.t();
            int day = month == null ? -1 : r.date().getDayOfMonth() - 1;
            switch (r.cls()) {
                case EARNED -> {
                    Long id = idOf(t.getCategory());
                    income.computeIfAbsent(id, k -> new CategorySum(t.getCategory())).add(r.amount());
                    incomeKinds.put(id, kind);
                }
                case EVERYDAY -> {
                    Category top = top(t.getCategory());
                    CategorySum sum = everyday.computeIfAbsent(idOf(top), k -> new CategorySum(top));
                    sum.add(r.amount());
                    Long own = idOf(t.getCategory());
                    if (own != null && !own.equals(idOf(top))) {
                        sum.children.computeIfAbsent(own, k -> new CategorySum(t.getCategory())).add(r.amount());
                    }
                    itemised.add(r);
                    if (day >= 0) {
                        dayItemised[day] = dayItemised[day].add(r.amount());
                        if (dayTop[day] == null || r.amount().compareTo(dayTop[day].amount()) > 0) dayTop[day] = r;
                    }
                }
                case EVERYDAY_UNITEMISED, CORRECTION -> {
                    if (day >= 0) dayUnitemised[day] = dayUnitemised[day].add(everydayPart(r));
                }
                case BILL -> {
                    BillSum sum = bills.computeIfAbsent(t.getMonthlyPaymentId(), k -> new BillSum());
                    sum.paid = sum.paid.add(r.amount());
                    sum.count++;
                    if (sum.latest == null || r.date().isAfter(sum.latest.getTransactionDate())) sum.latest = t;
                }
                case LOAN_PAYMENT -> {
                    LoanSum sum = loanSum(t);
                    sum.paid = sum.paid.add(r.amount());
                }
                case SAVED -> {
                    if (GOAL.equals(kind)) {
                        GoalSum sum = goalSum(t);
                        sum.saved = sum.saved.add(r.amount());
                    }
                }
                default -> { }
            }
        }

        /** The loan a payment paid, as one line's key — see {@link LoanLine}. */
        private LoanSum loanSum(Transaction t) {
            if (t.getSubType() == TransactionSubType.BANK_LOAN_PAYMENT) {
                if (bankLoans == null) bankLoans = uzs(bankLoanRepository.findAll());
                BankLoan b = bankLoanOf(t, bankLoans);
                return b == null
                        ? loans.computeIfAbsent(BANK + ":", k -> new LoanSum(BANK, null, BANK_LOAN, false))
                        : loans.computeIfAbsent(BANK + ":" + b.getId(),
                                k -> new LoanSum(BANK, b.getId(), DailyAdviceService.bankName(b), false));
            }
            if (t.getRepaidLoanTakenId() != null) {
                if (loansTaken == null) loansTaken = byId(loanTakenRepository.findAll(), LoanTaken::getId);
                LoanTaken l = loansTaken.get(t.getRepaidLoanTakenId());
                return loans.computeIfAbsent(LOAN + ":" + t.getRepaidLoanTakenId(), k -> new LoanSum(LOAN,
                        t.getRepaidLoanTakenId(), l == null ? t.getDescription() : l.getLenderName(),
                        l != null && l.effectiveRepaymentType() == RepaymentType.ASAP));
            }
            if (t.getRepaidDebtId() != null) {
                if (debts == null) debts = byId(debtRepository.findAll(), Debt::getId);
                Debt d = debts.get(t.getRepaidDebtId());
                return loans.computeIfAbsent(DEBT + ":" + t.getRepaidDebtId(), k -> new LoanSum(DEBT,
                        t.getRepaidDebtId(), d == null ? t.getDescription() : d.getCreditorName(), true));
            }
            // A repayment that names no loan: one line per description.
            return loans.computeIfAbsent(LOAN + ":?" + t.getDescription(),
                    k -> new LoanSum(LOAN, null, t.getDescription(), false));
        }

        /** The savings goal a row paid into: the holding it names, else the one it created. */
        private GoalSum goalSum(Transaction t) {
            if (holdingsById == null) {
                List<Investment> every = investmentRepository.findAll();
                holdingsById = byId(every, Investment::getId);
                holdingsByFirstRow = byId(every, Investment::getOriginatingTransactionId);
            }
            Investment goal = t.getInvestmentId() != null ? holdingsById.get(t.getInvestmentId()) : null;
            if (goal == null && t.getId() != null) goal = holdingsByFirstRow.get(t.getId());
            if (goal != null) {
                Investment g = goal;
                return goals.computeIfAbsent("#" + g.getId(), k -> new GoalSum(g.getId(), g.getName()));
            }
            // The goal is gone: the row's own words are all that is left of its name.
            return goals.computeIfAbsent(t.getInvestmentId() + "?" + t.getDescription(),
                    k -> new GoalSum(t.getInvestmentId(), t.getDescription()));
        }

        private List<IncomeLine> income() {
            List<IncomeLine> lines = new ArrayList<>();
            for (Map.Entry<Long, CategorySum> e : income.entrySet()) {
                Category c = e.getValue().category;
                lines.add(IncomeLine.builder().categoryId(e.getKey())
                        .name(c == null ? UNCATEGORIZED : c.getName()).nameUz(c == null ? null : c.getNameUz())
                        .kind(incomeKinds.get(e.getKey())).amount(e.getValue().amount).build());
            }
            lines.sort(Comparator.comparing(IncomeLine::getAmount).reversed());
            return lines;
        }

        private List<EverydayCategory> categories(Map<Long, BigDecimal> previousByCategory) {
            List<EverydayCategory> lines = new ArrayList<>();
            for (Map.Entry<Long, CategorySum> e : everyday.entrySet()) {
                CategorySum sum = e.getValue();
                Category c = sum.category;
                List<EverydayChild> children = new ArrayList<>();
                for (Map.Entry<Long, CategorySum> ch : sum.children.entrySet()) {
                    Category cc = ch.getValue().category;
                    children.add(EverydayChild.builder().categoryId(ch.getKey()).name(cc.getName())
                            .nameUz(cc.getNameUz()).amount(ch.getValue().amount).count(ch.getValue().count).build());
                }
                children.sort(Comparator.comparing(EverydayChild::getAmount).reversed());
                lines.add(EverydayCategory.builder().categoryId(e.getKey())
                        .name(c == null ? UNCATEGORIZED : c.getName()).nameUz(c == null ? null : c.getNameUz())
                        .color(c == null ? null : c.getColor())
                        .amount(sum.amount).count(sum.count)
                        .previousAmount(previousByCategory == null ? null
                                : previousByCategory.getOrDefault(e.getKey(), BigDecimal.ZERO))
                        .children(children).build());
            }
            lines.sort(Comparator.comparing(EverydayCategory::getAmount).reversed());
            return lines;
        }

        /** One row per day from the 1st to min(month end, {@code date}), zero days included. */
        private List<Day> daily(YearMonth m, LocalDate date) {
            LocalDate last = date.isBefore(m.atEndOfMonth()) ? date : m.atEndOfMonth();
            List<Day> rows = new ArrayList<>();
            BigDecimal running = BigDecimal.ZERO;
            for (LocalDate d = m.atDay(1); !d.isAfter(last); d = d.plusDays(1)) {
                int i = d.getDayOfMonth() - 1;
                BigDecimal amount = dayItemised[i].add(dayUnitemised[i]);
                running = running.add(amount);
                rows.add(Day.builder().date(d).amount(amount).unitemised(dayUnitemised[i]).cumulative(running).build());
            }
            return rows;
        }

        /** The three days with the most itemised everyday spending. */
        private List<BiggestDay> biggestDays(YearMonth m) {
            List<BiggestDay> rows = new ArrayList<>();
            for (int i = 0; i < dayItemised.length; i++) {
                if (dayItemised[i].signum() <= 0) continue;
                rows.add(BiggestDay.builder().date(m.atDay(i + 1)).amount(dayItemised[i])
                        .topDescription(dayTop[i] == null ? null : dayTop[i].t().getDescription()).build());
            }
            rows.sort(Comparator.comparing(BiggestDay::getAmount).reversed().thenComparing(BiggestDay::getDate));
            return rows.size() > 3 ? new ArrayList<>(rows.subList(0, 3)) : rows;
        }

        /** The five largest itemised rows. */
        private List<BiggestRow> biggest() {
            List<Row> rows = new ArrayList<>(itemised);
            rows.sort(Comparator.comparing(Row::amount).reversed()
                    .thenComparing(Row::date, Comparator.reverseOrder()));
            List<BiggestRow> out = new ArrayList<>();
            for (Row r : rows.size() > 5 ? rows.subList(0, 5) : rows) {
                Category c = r.t().getCategory();
                Category top = top(c);
                out.add(BiggestRow.builder().id(r.t().getId()).date(r.date()).description(r.t().getDescription())
                        .amount(r.amount()).categoryId(idOf(c))
                        .categoryName(c == null ? UNCATEGORIZED : c.getName())
                        .categoryNameUz(c == null ? null : c.getNameUz())
                        .color(c == null ? null : c.getColor() != null ? c.getColor() : top.getColor())
                        .build());
            }
            return out;
        }

        private List<BillLine> bills(Map<Long, String> names) {
            List<BillLine> lines = new ArrayList<>();
            for (Map.Entry<Long, BillSum> e : bills.entrySet()) {
                String name = names.get(e.getKey());
                lines.add(BillLine.builder().refId(e.getKey())
                        .name(name != null ? name : e.getValue().latest.getDescription())
                        .paid(e.getValue().paid).count(e.getValue().count).build());
            }
            lines.sort(Comparator.comparing(BillLine::getPaid).reversed());
            return lines;
        }

        private List<LoanLine> loanPayments() {
            List<LoanLine> lines = new ArrayList<>();
            for (LoanSum s : loans.values()) {
                lines.add(LoanLine.builder().kind(s.kind).refId(s.refId).name(s.name).asap(s.asap).paid(s.paid).build());
            }
            lines.sort(Comparator.comparing(LoanLine::getPaid).reversed());
            return lines;
        }
    }

    /**
     * The bank loan an installment paid, when it can be told — the row names no loan. It is the one
     * loan whose bank (and, between several at one bank, product) the description mentions, as the
     * web's and the bot's Pay write it; else the only loan that ran in the row's month; else unknown.
     */
    static BankLoan bankLoanOf(Transaction t, List<BankLoan> loans) {
        String words = t.getDescription() == null ? "" : t.getDescription().toLowerCase(Locale.ROOT);
        List<BankLoan> named = loans.stream().filter(b -> mentions(words, b.getBankName())).toList();
        if (named.size() > 1) {
            List<BankLoan> product = named.stream().filter(b -> mentions(words, b.getLoanName())).toList();
            if (product.size() == 1) return product.get(0);
        }
        if (named.size() == 1) return named.get(0);
        YearMonth month = YearMonth.from(t.getTransactionDate());
        List<BankLoan> running = (named.isEmpty() ? loans : named).stream()
                .filter(b -> OverviewService.bankLoanRunsIn(b, month)).toList();
        return running.size() == 1 ? running.get(0) : null;
    }

    private static boolean mentions(String words, String name) {
        return name != null && !name.isBlank() && words.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    private Map<Long, String> billNames() {
        Map<Long, String> names = new HashMap<>();
        List<MonthlyPayment> bills = monthlyPaymentRepository.findAll();
        for (MonthlyPayment m : bills == null ? List.<MonthlyPayment>of() : bills) names.put(m.getId(), m.getName());
        return names;
    }

    // ── Savings: what was put by, and what the month asked ────────────────────

    /**
     * DONATION, EMERGENCY, INVESTMENTS always, then each savings goal that received money in the
     * range or has an ask. {@code asked} only for a one-month range ({@code month} not null) — the
     * current month from the advisor's savings rows, a past one from the ledger; both are read, never
     * recomputed here.
     */
    private List<SavingLine> savings(Flow totals, Details details, List<Investment> holdings,
                                     YearMonth month, LocalDate date) {
        Map<String, BigDecimal> asked = new HashMap<>();
        Map<Long, BigDecimal> goalAsked = new LinkedHashMap<>();
        if (month != null && month.equals(YearMonth.from(date))) {
            for (SavingsRow row : advisorService.savingsThisMonth(date)) {
                BigDecimal ask = nz(row.getTarget()).add(nz(row.getCarried()));
                if (GOAL.equals(row.getBucket())) goalAsked.put(row.getRefId(), ask);
                else asked.put(row.getBucket(), ask);
            }
        } else if (month != null) {
            AllocationLedgerResponse ledger = overviewService.getAllocationLedger(month, Currency.UZS);
            List<AllocationLedgerResponse.BucketLedger> buckets = ledger == null || ledger.getBuckets() == null
                    ? List.of() : ledger.getBuckets();
            for (AllocationLedgerResponse.BucketLedger b : buckets) {
                asked.put(b.getBucket(), nz(b.getRecommended()).add(nz(b.getCarried())));
            }
            // A goal keeps no history of its plan: a past month asked its monthly payment, from the
            // month that payment started.
            for (Investment g : holdings) {
                if (!Investment.PLAN.equals(g.goalKind())) continue;   // a wish asks for nothing
                YearMonth starts = g.paymentStartMonth();
                if (starts != null && starts.isAfter(month)) continue;
                goalAsked.put(g.getId(), nz(g.getMonthlyContribution()));
            }
        }

        List<SavingLine> lines = new ArrayList<>();
        lines.add(bucketLine(DONATION, totals.getSavedDonation(), asked));
        lines.add(bucketLine(EMERGENCY, totals.getSavedEmergency(), asked));
        lines.add(bucketLine(INVESTMENTS, totals.getSavedInvestments(), asked));

        List<SavingLine> goals = new ArrayList<>();
        for (GoalSum g : details.goals.values()) {
            goals.add(SavingLine.builder().kind(GOAL).refId(g.refId).name(g.name).saved(g.saved)
                    .asked(g.refId == null ? null : positive(goalAsked.remove(g.refId))).build());
        }
        Map<Long, Investment> byId = byId(holdings, Investment::getId);
        for (Map.Entry<Long, BigDecimal> e : goalAsked.entrySet()) {
            if (positive(e.getValue()) == null) continue;
            Investment g = byId.get(e.getKey());
            goals.add(SavingLine.builder().kind(GOAL).refId(e.getKey()).name(g == null ? null : g.getName())
                    .saved(BigDecimal.ZERO).asked(e.getValue()).build());
        }
        goals.sort(Comparator.comparing(SavingLine::getSaved).reversed()
                .thenComparing(l -> nz(l.getAsked()), Comparator.reverseOrder()));
        lines.addAll(goals);
        return lines;
    }

    private static SavingLine bucketLine(String kind, BigDecimal saved, Map<String, BigDecimal> asked) {
        return SavingLine.builder().kind(kind).saved(saved).asked(positive(asked.get(kind))).build();
    }

    // ── Position: own and owe, today ──────────────────────────────────────────

    private List<Investment> uzsHoldings() {
        List<Investment> every = investmentRepository.findAll();
        List<Investment> out = new ArrayList<>();
        for (Investment i : every == null ? List.<Investment>of() : every) {
            if (i.getCurrency() == null || i.getCurrency() == Currency.UZS) out.add(i);
        }
        return out;
    }

    private Position position(LocalDate date, List<Investment> holdings) {
        // The advisor's `have`: every UZS wallet's computed balance at the end of the day.
        BigDecimal wallets = BigDecimal.ZERO;
        for (MonthCloseService.ComputedWallet w : monthCloseService.computedWallets(date, Set.of())) {
            if (w.currency() != null && w.currency() != Currency.UZS) continue;
            wallets = wallets.add(nz(w.computed()));
        }

        // The Savings page's split: a goal, else the emergency fund, else a plain investment.
        BigDecimal emergencyFund = BigDecimal.ZERO;
        BigDecimal investments = BigDecimal.ZERO;
        BigDecimal goals = BigDecimal.ZERO;
        for (Investment i : holdings) {
            BigDecimal value = AdvisorService.value(i);
            if (Boolean.TRUE.equals(i.getSavingsGoal())) goals = goals.add(value);
            else if (Boolean.TRUE.equals(i.getEmergencyFund())) emergencyFund = emergencyFund.add(value);
            else investments = investments.add(value);
        }
        List<Emergency> contributions = emergencyRepository.findAllByOrderByDateDesc();
        for (Emergency e : contributions == null ? List.<Emergency>of() : contributions) {
            if (e.getAmount() == null || (e.getCurrency() != null && e.getCurrency() != Currency.UZS)) continue;
            if (e.getDate() != null && e.getDate().isAfter(date)) continue;
            emergencyFund = emergencyFund.add(e.getAmount());
        }
        BigDecimal own = wallets.add(emergencyFund).add(investments).add(goals);

        // The same list, and the same `left`, the advisor's `owe` adds up.
        List<PositionLoan> loans = new ArrayList<>();
        for (OverviewService.OpenLoan l : overviewService.openLoans(date)) {
            loans.add(PositionLoan.builder().kind(l.kind()).refId(l.refId()).name(l.name()).asap(l.asap())
                    .original(l.original()).left(l.left()).monthly(l.monthly())
                    .paidOffBy(l.paidOffBy() == null ? null : l.paidOffBy().toString()).build());
        }
        BigDecimal loansLeft = BigDecimal.ZERO;
        for (PositionLoan l : loans) if (l.getLeft() != null) loansLeft = loansLeft.add(l.getLeft());

        BigDecimal owed = BigDecimal.ZERO;
        List<LoanGiven> given = loanGivenRepository.findAll();
        for (LoanGiven l : given == null ? List.<LoanGiven>of() : given) owed = owed.add(AdvisorService.stillOwedToOwner(l));

        return Position.builder().asOf(date).wallets(wallets).emergencyFund(emergencyFund)
                .investments(investments).goals(goals).own(own).loans(loans).loansLeft(loansLeft)
                .owedToYou(owed).net(own.subtract(loansLeft)).build();
    }

    /**
     * Note the position down as this month's (PositionSnapshot) — only when {@code asOf} really is
     * today, give or take the day the owner's clock may be ahead of or behind the server's: a page
     * asked for some other day must not rewrite a month's record with today's investment values.
     * It runs in a transaction of its own and is never allowed to fail the read.
     */
    private void remember(Position p) {
        if (Math.abs(ChronoUnit.DAYS.between(LocalDate.now(), p.getAsOf())) > 1) return;
        try {
            positionSnapshotService.record(YearMonth.from(p.getAsOf()), p.getAsOf(), p.getWallets(),
                    p.getEmergencyFund(), p.getInvestments(), p.getGoals(), p.getLoansLeft(), p.getOwedToYou());
        } catch (RuntimeException e) {
            log.warn("Could not record the position for {}: {}", p.getAsOf(), e.toString());
        }
    }

    private List<PositionMonth> positionHistory() {
        List<PositionSnapshot> rows = positionSnapshotService.history();
        List<PositionMonth> out = new ArrayList<>();
        for (PositionSnapshot s : rows == null ? List.<PositionSnapshot>of() : rows) {
            BigDecimal own = nz(s.getWallets()).add(nz(s.getEmergencyFund())).add(nz(s.getInvestments())).add(nz(s.getGoals()));
            out.add(PositionMonth.builder().month(YearMonth.from(s.getMonth()).toString()).asOf(s.getAsOf())
                    .wallets(s.getWallets()).emergencyFund(s.getEmergencyFund()).investments(s.getInvestments())
                    .goals(s.getGoals()).own(own).loansLeft(s.getLoansLeft()).owedToYou(s.getOwedToYou())
                    .net(own.subtract(nz(s.getLoansLeft()))).build());
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The top of a category's tree; null for no category. */
    private static Category top(Category c) {
        return c == null ? null : OverviewService.rootOf(c);
    }

    private static Long idOf(Category c) {
        return c == null ? null : c.getId();
    }

    /** Every day 1..n with the running total. */
    private static List<PreviousDay> cumulative(BigDecimal[] byDay) {
        List<PreviousDay> rows = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < byDay.length; i++) {
            running = running.add(byDay[i]);
            rows.add(PreviousDay.builder().day(i + 1).cumulative(running).build());
        }
        return rows;
    }

    private static List<BankLoan> uzs(List<BankLoan> loans) {
        List<BankLoan> out = new ArrayList<>();
        for (BankLoan b : loans == null ? List.<BankLoan>of() : loans) {
            if (b.getCurrency() == null || b.getCurrency() == Currency.UZS) out.add(b);
        }
        return out;
    }

    private static <T> Map<Long, T> byId(List<T> rows, java.util.function.Function<T, Long> id) {
        Map<Long, T> out = new HashMap<>();
        for (T row : rows == null ? List.<T>of() : rows) {
            Long key = id.apply(row);
            if (key != null) out.put(key, row);
        }
        return out;
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** The amount when it is more than nothing; else null. */
    private static BigDecimal positive(BigDecimal v) {
        return v == null || v.signum() <= 0 ? null : v;
    }
}
