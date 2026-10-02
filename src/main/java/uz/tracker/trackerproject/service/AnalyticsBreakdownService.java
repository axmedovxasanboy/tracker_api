package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.Average;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.DayEveryday;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.Estimate;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.Expected;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.Goal;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.GoalMonth;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.History;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.Holding;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.Line;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.LineHistory;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.MonthFlow;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.OtherMonth;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.Received;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.SinceStart;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse.TopRow;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Flow;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.HoldingMonthSnapshot;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionFlow;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.HoldingMonthSnapshotRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.MonthlyPaymentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;
import uz.tracker.trackerproject.service.AnalyticsService.FlowClass;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Analytics V2's one read ({@code GET /analytics/breakdown}, ANALYTICS-V2-SPEC §4): for a range of
 * months, the flow month by month, what a month is expected to be, and every line behind In, Out,
 * Set aside and what moved — plus the savings goals month by month and what each holding is worth.
 *
 * <p><b>Nothing is classified a second time.</b> A row counts when {@link AnalyticsService#counts}
 * says so, in {@link AnalyticsService#monthOf}'s month (a salary marked for a month counts in it),
 * as {@link TransactionFlows#of} / {@link AnalyticsService#classify} make it, booked into the month's
 * {@link Flow} by {@link AnalyticsService#book}; earned money is split by
 * {@link AnalyticsService#incomeKind} and saved money by {@link AnalyticsService#savedKind} — the
 * very calls {@code GET /analytics} makes, so the two endpoints can never disagree about a month.
 *
 * <p><b>History start</b> (§1.2) is the latest of Settings' tracking start, the first month with
 * earned money and the first month with everyday spending or a bill; with neither of the last two,
 * the first month with any counted row. Months before it are never shown or averaged. A month from
 * it on with a counted row is <i>tracked</i>; one without is left out of every average.
 *
 * <p><b>Expected</b> (a one-month range): the average of the tracked months before it that have
 * ended, rounded once, half-up, to the so'm; everyday spending is scaled by days (a 31-day month
 * expects 31 days of it). Out and Left over are worked out from the rounded parts.
 *
 * <p><b>Read-only, and cheap:</b> the rows are read once (to the end of the month after the owner's,
 * as {@code GET /analytics} reads them), every category once, every table once; no ledger, plan or
 * advisor is rebuilt, and nothing is written — the holdings' monthly snapshots are written by
 * {@link SnapshotScheduler}.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsBreakdownService {

    /** Before any row the owner could have recorded. */
    private static final LocalDate BEGINNING = LocalDate.of(1970, 1, 1);
    /** A goal's months are capped at the last two years. */
    private static final int GOAL_MONTHS = 24;
    /** How many of a category's largest everyday rows are listed. */
    private static final int TOP_ROWS = 3;

    // Line keys and kinds (§4.3).
    static final String UNCATEGORIZED = "uncategorized";
    static final String UNITEMISED = "unitemised";
    static final String LOANS = "loans";
    static final String CATEGORY = "CATEGORY";
    static final String BILL = "BILL";
    static final String LOAN = "LOAN";
    static final String NOT_ITEMISED = "NOT_ITEMISED";
    static final String CHECK = "CHECK";
    static final String GROUP = "GROUP";
    static final String HOLDING = "HOLDING";
    static final String GOAL = "GOAL";
    static final String DONATION_KIND = "DONATION_KIND";
    static final String PERSON = "PERSON";

    /** Set aside's groups, in the page's fixed order. */
    static final List<String> SET_ASIDE_GROUPS = List.of("INVESTMENTS", "EMERGENCY", "GOALS", "DONATIONS");
    /** What moved but is no income or spending, in the page's fixed order. */
    static final List<TransactionFlow> MOVED_FLOWS = List.of(TransactionFlow.BORROWED, TransactionFlow.LENT,
            TransactionFlow.RETURNED, TransactionFlow.FROM_SAVINGS);

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final InvestmentRepository investmentRepository;
    private final LoanTakenRepository loanTakenRepository;
    private final LoanGivenRepository loanGivenRepository;
    private final DebtRepository debtRepository;
    private final BankLoanRepository bankLoanRepository;
    private final MonthlyPaymentRepository monthlyPaymentRepository;
    private final HoldingMonthSnapshotRepository snapshotRepository;
    private final SettingsService settingsService;
    private final OverviewService overviewService;

    /** The four line lists. */
    private enum Part { INCOME, OUT, SET_ASIDE, MOVED }

    /** A counted row with its month, its flow and class, and — earned or saved money — its kind. */
    private record Row(Transaction t, YearMonth month, TransactionFlow flow, FlowClass cls, String kind) {
        BigDecimal amount() {
            return t.getAmount();
        }

        LocalDate date() {
            return t.getTransactionDate();
        }
    }

    /** What a line is — everything about it but its figures. */
    private record Ref(String key, String kind, Long refId, String name, String nameUz, String loanKind,
                       TransactionFlow flow) {
        static Ref of(String key, String kind, Long refId, String name, String nameUz) {
            return new Ref(key, kind, refId, name, nameUz, null, null);
        }
    }

    /** A line within its list: {@code child} is null for a top line. */
    private record LineId(Part part, String top, String child) { }

    /**
     * Where a row goes: its top line and the child line under it (null: none), and whether its
     * amount is everyday spending (scaled by days in an expected figure) or anything else.
     */
    private record Place(Part part, Ref top, Ref child, boolean everyday, BigDecimal amount) { }

    /** One line's rows in one month. */
    private static final class Sum {
        private BigDecimal everyday = BigDecimal.ZERO;
        private BigDecimal other = BigDecimal.ZERO;
        private final List<Row> rows = new ArrayList<>();

        private void add(Row r, Place p) {
            if (p.everyday()) everyday = everyday.add(p.amount());
            else other = other.add(p.amount());
            rows.add(r);
        }

        private BigDecimal amount() {
            return everyday.add(other);
        }
    }

    /** Every table the breakdown reads, read once. */
    private record Tables(Map<Long, Category> categories, Set<Long> salaryTree,
                          List<Investment> holdings, Map<Long, Investment> holdingsById,
                          Map<Long, Investment> holdingsByFirstRow, Map<Long, LoanTaken> loansTaken,
                          Map<Long, LoanTaken> loansTakenByFirstRow, Map<Long, LoanGiven> loansGiven,
                          Map<Long, LoanGiven> loansGivenByFirstRow, Map<Long, Debt> debts,
                          List<BankLoan> bankLoans, Map<Long, MonthlyPayment> bills, Category donationRoot) {

        /** The preloaded category for a row's (possibly lazy) category; null for none. */
        Category category(Category c) {
            if (c == null) return null;
            Category loaded = c.getId() == null ? null : categories.get(c.getId());
            return loaded != null ? loaded : c;
        }

        /** {@link OverviewService#rootOf}, its parents resolved from the preloaded map. */
        Category rootOf(Category c) {
            Category at = category(c);
            if (at == null) return null;
            for (int depth = 0; at.getParent() != null && depth < 32; depth++) at = category(at.getParent());
            return at;
        }

        /** The holding a row paid into: the one it names, else the one it created. */
        Investment holdingOf(Transaction t) {
            Investment h = t.getInvestmentId() == null ? null : holdingsById.get(t.getInvestmentId());
            if (h == null && t.getId() != null) h = holdingsByFirstRow.get(t.getId());
            return h;
        }
    }

    /** What the request covers, once worked out. */
    private record Scope(LocalDate date, YearMonth current, YearMonth from, YearMonth to, YearMonth start,
                         List<YearMonth> range, List<YearMonth> base, Set<YearMonth> tracked) {
        boolean oneMonth() {
            return from.equals(to);
        }

        boolean hasBase() {
            return oneMonth() && !base.isEmpty();
        }

        /** The complete tracked months of the range — a range's averages. */
        List<YearMonth> completeTracked() {
            return range.stream().filter(m -> m.isBefore(current) && tracked.contains(m)).toList();
        }
    }

    // ── The endpoint ──────────────────────────────────────────────────────────

    /**
     * @param fromArg first month of the range; null = {@code toArg}
     * @param toArg   last month of the range; null = the month of {@code date}
     * @param date    the owner's local day
     */
    @Transactional(readOnly = true)
    public AnalyticsBreakdownResponse breakdown(YearMonth fromArg, YearMonth toArg, LocalDate date) {
        // The range: the same rules and words as GET /analytics.
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
        if (span > AnalyticsService.MAX_MONTHS) {
            throw new IllegalArgumentException("The range can't be longer than " + AnalyticsService.MAX_MONTHS
                    + " months, got: " + span);
        }

        Tables tables = tables();
        List<Transaction> all = transactionRepository.findByTransactionDateBetween(BEGINNING, current.plusMonths(1).atEndOfMonth());
        if (all == null) all = List.of();

        // ── The counted rows (dated by the owner's day); later ones are only counted as not yet ──
        List<Row> rows = new ArrayList<>();
        // Every counted row by the month it counts in, whatever its day: what History's month view
        // lists, so a link to History is held to these (a row recorded ahead for its day included).
        Map<YearMonth, List<Row>> listed = new HashMap<>();
        int notYet = 0;
        for (Transaction t : all) {
            if (!AnalyticsService.counts(t)) continue;
            YearMonth month = AnalyticsService.monthOf(t);
            FlowClass cls = AnalyticsService.classify(t);
            String kind = switch (cls) {
                case EARNED -> AnalyticsService.incomeKind(tables.category(t.getCategory()), tables.salaryTree());
                case SAVED -> AnalyticsService.savedKind(t, overviewService);
                default -> null;
            };
            Row row = new Row(t, month, TransactionFlows.of(t), cls, kind);
            listed.computeIfAbsent(month, k -> new ArrayList<>()).add(row);
            if (t.getTransactionDate().isAfter(date)) {
                if (within(month, from, to)) notYet++;
                continue;
            }
            rows.add(row);
        }

        // ── History start (§1.2): rows counted after the owner's month are no data yet ──
        Settings settings = settingsService.getOrCreate();
        YearMonth trackingStart = settings == null || settings.getAllocationTrackingStartMonth() == null
                ? null : YearMonth.from(settings.getAllocationTrackingStartMonth());
        YearMonth firstEarned = null;
        YearMonth firstOut = null;
        YearMonth firstAny = null;
        for (Row r : rows) {
            if (r.month().isAfter(current)) continue;
            firstAny = earlier(firstAny, r.month());
            if (r.cls() == FlowClass.EARNED) firstEarned = earlier(firstEarned, r.month());
            if (r.flow() == TransactionFlow.EVERYDAY || r.flow() == TransactionFlow.BILL) firstOut = earlier(firstOut, r.month());
        }
        YearMonth start;
        if (firstAny == null) start = null;
        else if (firstEarned == null && firstOut == null) start = firstAny;
        else start = later(later(trackingStart, firstEarned), firstOut);

        // ── Each month from the start to the owner's, booked once ──
        Map<YearMonth, MonthFlow> flows = new TreeMap<>();
        Map<YearMonth, List<Row>> rowsByMonth = new HashMap<>();
        if (start != null) {
            for (YearMonth m = start; !m.isAfter(current); m = m.plusMonths(1)) rowsByMonth.put(m, new ArrayList<>());
            for (Row r : rows) {
                List<Row> list = rowsByMonth.get(r.month());
                if (list != null) list.add(r);
            }
            for (YearMonth m = start; !m.isAfter(current); m = m.plusMonths(1)) {
                boolean complete = m.isBefore(current);
                List<Row> monthRows = rowsByMonth.get(m);
                MonthFlow f = new MonthFlow(m.toString(), complete, !monthRows.isEmpty(),
                        complete ? m.lengthOfMonth() : date.getDayOfMonth(), m.lengthOfMonth());
                for (Row r : monthRows) AnalyticsService.book(f, r.cls(), r.amount(), r.kind());
                flows.put(m, f);
            }
            // Pay that reached the wallets in another month than it counts in, as GET /analytics has it.
            for (Row r : rows) {
                YearMonth arrived = YearMonth.from(r.date());
                if (arrived.equals(r.month())) continue;
                BigDecimal wallet = r.t().getType() == TransactionType.INCOME ? r.amount() : r.amount().negate();
                MonthFlow counted = flows.get(r.month());
                if (counted != null) counted.addPayForOtherMonths(wallet.negate());
                MonthFlow reached = flows.get(arrived);
                if (reached != null) reached.addPayForOtherMonths(wallet);
            }
            for (MonthFlow f : flows.values()) f.settle();
        }
        Set<YearMonth> tracked = new TreeSet<>();
        for (Map.Entry<YearMonth, MonthFlow> e : flows.entrySet()) if (e.getValue().isTracked()) tracked.add(e.getKey());

        List<YearMonth> range = new ArrayList<>();
        if (start != null) {
            for (YearMonth m = from.isBefore(start) ? start : from; !m.isAfter(to); m = m.plusMonths(1)) range.add(m);
        }
        List<YearMonth> base = new ArrayList<>();
        if (from.equals(to)) {
            for (YearMonth m : tracked) if (m.isBefore(to) && m.isBefore(current)) base.add(m);
        }
        Scope scope = new Scope(date, current, from, to, start, range, base, tracked);

        // ── The flow of the range ──
        List<MonthFlow> months = new ArrayList<>();
        Flow total = new Flow();
        for (YearMonth m : range) {
            months.add(flows.get(m));
            total.add(flows.get(m));
        }
        total.settle();

        // ── The lines: every row placed once, by month ──
        Map<YearMonth, Map<LineId, Sum>> sums = new HashMap<>();
        Map<LineId, Ref> refs = new HashMap<>();
        for (Map.Entry<YearMonth, List<Row>> e : rowsByMonth.entrySet()) {
            Map<LineId, Sum> monthSums = new HashMap<>();
            for (Row r : e.getValue()) {
                Place p = place(r, tables);
                LineId top = new LineId(p.part(), p.top().key(), null);
                refs.merge(top, p.top(), AnalyticsBreakdownService::keepRefId);
                monthSums.computeIfAbsent(top, k -> new Sum()).add(r, p);
                if (p.child() != null) {
                    LineId child = new LineId(p.part(), p.top().key(), p.child().key());
                    refs.merge(child, p.child(), AnalyticsBreakdownService::keepRefId);
                    monthSums.computeIfAbsent(child, k -> new Sum()).add(r, p);
                }
            }
            sums.put(e.getKey(), monthSums);
        }
        Lines lines = new Lines(scope, sums, refs, rows, listed, tables);

        // ── Expected (one month with a base) or the average (a range) ──
        Expected expected = null;
        if (scope.hasBase()) {
            List<Flow> baseFlows = base.stream().map(m -> (Flow) flows.get(m)).toList();
            int baseDays = base.stream().mapToInt(YearMonth::lengthOfMonth).sum();
            int days = to.lengthOfMonth();
            List<YearMonth> skipped = new ArrayList<>();
            for (YearMonth m = start; m.isBefore(to); m = m.plusMonths(1)) if (!tracked.contains(m)) skipped.add(m);
            BigDecimal itemised = BigDecimal.ZERO;
            for (Flow f : baseFlows) itemised = itemised.add(f.getEveryday().subtract(f.getEverydayUnitemised()));
            List<Line> loanLines = lines.list(Part.OUT).stream().filter(l -> LOANS.equals(l.getKey()))
                    .flatMap(l -> l.getChildren().stream()).toList();
            BigDecimal paidOff = BigDecimal.ZERO;
            for (Line l : loanLines) if (l.isClosed() && l.getExpected() != null) paidOff = paidOff.add(l.getExpected());
            expected = Expected.builder()
                    .month(to.toString())
                    .basedOn(base.stream().map(YearMonth::toString).toList())
                    .skipped(skipped.stream().map(YearMonth::toString).toList())
                    .dayFactor(BigDecimal.valueOf((long) days * base.size())
                            .divide(BigDecimal.valueOf(baseDays), 4, RoundingMode.HALF_UP))
                    .flow(estimate(baseFlows, days, baseDays))
                    .everydayByToday(to.equals(current)
                            ? scaled(itemised, date.getDayOfMonth(), baseDays) : null)
                    .paidOffLoans(paidOff)
                    .build();
        }
        Average average = null;
        if (from.isBefore(to) && !scope.completeTracked().isEmpty()) {
            List<YearMonth> complete = scope.completeTracked();
            average = Average.builder()
                    .basedOn(complete.stream().map(YearMonth::toString).toList())
                    .flow(estimate(complete.stream().map(m -> (Flow) flows.get(m)).toList(), 0, 0))
                    .build();
        }

        // ── Set aside and taken out from the start to today, whatever range is open ──
        SinceStart sinceStart = null;
        if (start != null) {
            BigDecimal setAside = BigDecimal.ZERO;
            BigDecimal fromSavings = BigDecimal.ZERO;
            for (MonthFlow f : flows.values()) {
                setAside = setAside.add(f.getSaved());
                fromSavings = fromSavings.add(f.getFromSavings());
            }
            sinceStart = SinceStart.builder().from(start.toString()).setAside(setAside).fromSavings(fromSavings).build();
        }

        // ── Income dated in the range that counts outside it ──
        List<Received> received = new ArrayList<>();
        for (Row r : rows) {
            if (!within(YearMonth.from(r.date()), from, to) || within(r.month(), from, to)) continue;
            Category c = tables.category(r.t().getCategory());
            received.add(Received.builder().date(r.date()).countedIn(r.month().toString()).amount(r.amount())
                    .categoryId(c == null ? null : c.getId()).name(c == null ? AnalyticsService.UNCATEGORIZED : c.getName())
                    .nameUz(c == null ? null : c.getNameUz()).build());
        }
        received.sort(Comparator.comparing(Received::getDate));

        BigDecimal stable = overviewService.stableIncomeFor(to);
        return AnalyticsBreakdownResponse.builder()
                .currency(Currency.UZS)
                .date(date)
                .from(from.toString())
                .to(to.toString())
                .history(History.builder()
                        .start(text(start)).firstEarned(text(firstEarned)).firstOut(text(firstOut))
                        .trackingStart(text(trackingStart))
                        .tracked(tracked.stream().map(YearMonth::toString).toList())
                        .build())
                .notYetCount(notYet)
                .stableIncome(stable == null || stable.signum() <= 0 ? null : stable)
                .months(months)
                .total(total)
                .sinceStart(sinceStart)
                .expected(expected)
                .average(average)
                .receivedForOtherMonths(received)
                .everydayDaily(everydayDaily(scope, rowsByMonth))
                .income(lines.list(Part.INCOME))
                .out(lines.list(Part.OUT))
                .setAside(lines.list(Part.SET_ASIDE))
                .moved(lines.list(Part.MOVED))
                .goals(goals(scope, rows, listed, all, tables))
                .holdingsNow(holdingsNow(date, rows, tables))
                .build();
    }

    private Tables tables() {
        List<Category> categories = orEmpty(categoryRepository.findAll());
        Map<Long, Category> byId = byKey(categories, Category::getId);
        Category donationRoot = categories.stream()
                .filter(c -> c.getParent() == null && c.getApplicableSubType() == TransactionSubType.DONATION)
                .findFirst().orElse(null);
        List<Investment> holdings = orEmpty(investmentRepository.findAll());
        List<LoanTaken> taken = orEmpty(loanTakenRepository.findAll());
        List<LoanGiven> given = orEmpty(loanGivenRepository.findAll());
        List<BankLoan> bankLoans = new ArrayList<>();
        for (BankLoan b : orEmpty(bankLoanRepository.findAll())) {
            if (b.getCurrency() == null || b.getCurrency() == Currency.UZS) bankLoans.add(b);
        }
        return new Tables(byId, OverviewService.salaryTree(categories),
                holdings, byKey(holdings, Investment::getId), byKey(holdings, Investment::getOriginatingTransactionId),
                byKey(taken, LoanTaken::getId), byKey(taken, LoanTaken::getOriginatingTransactionId),
                byKey(given, LoanGiven::getId), byKey(given, LoanGiven::getOriginatingTransactionId),
                byKey(orEmpty(debtRepository.findAll()), Debt::getId), bankLoans,
                byKey(orEmpty(monthlyPaymentRepository.findAll()), MonthlyPayment::getId), donationRoot);
    }

    // ── Where a row goes (§4.4) ───────────────────────────────────────────────

    private static Place place(Row r, Tables tables) {
        Transaction t = r.t();
        return switch (r.cls()) {
            case EARNED -> categoryPlace(Part.INCOME, t.getCategory(), tables, false, r.amount());
            case EVERYDAY -> categoryPlace(Part.OUT, t.getCategory(), tables, true, r.amount());
            case EVERYDAY_UNITEMISED, CORRECTION -> {
                Category root = tables.rootOf(t.getCategory());
                Ref top = Ref.of(UNITEMISED, NOT_ITEMISED, root == null ? null : root.getId(), "Not itemised", null);
                String day = r.date().toString();
                BigDecimal amount = r.cls() == FlowClass.CORRECTION ? r.amount().negate() : r.amount();
                yield new Place(Part.OUT, top, Ref.of("check:" + day, CHECK, null, day, null), true, amount);
            }
            case BILL -> {
                MonthlyPayment bill = tables.bills().get(t.getMonthlyPaymentId());
                Category root = tables.rootOf(t.getCategory());
                if (root == null && bill != null) root = tables.rootOf(bill.getCategory());
                Ref top = root == null ? uncategorized() : categoryRef(root);
                Ref child = Ref.of("bill:" + t.getMonthlyPaymentId(), BILL, t.getMonthlyPaymentId(),
                        bill != null && bill.getName() != null ? bill.getName() : t.getDescription(), null);
                yield new Place(Part.OUT, top, child, false, r.amount());
            }
            case LOAN_PAYMENT -> new Place(Part.OUT, Ref.of(LOANS, GROUP, null, "Loan payments", null),
                    loanRef(t, tables), false, r.amount());
            case SAVED -> savedPlace(r, tables);
            case BORROWED -> {
                LoanTaken l = tables.loansTakenByFirstRow().get(t.getId());
                Ref child = l != null ? Ref.of("loanTaken:" + l.getId(), PERSON, l.getId(), l.getLenderName(), null)
                        : description(t, PERSON);
                yield movedPlace(r, child);
            }
            case LENT -> {
                LoanGiven l = t.getId() == null ? null : tables.loansGivenByFirstRow().get(t.getId());
                if (l == null && t.getLoanGivenId() != null) l = tables.loansGiven().get(t.getLoanGivenId());
                yield movedPlace(r, l != null ? loanGivenRef(l) : description(t, PERSON));
            }
            case RETURNED -> {
                LoanGiven l = t.getRepaidLoanGivenId() == null ? null : tables.loansGiven().get(t.getRepaidLoanGivenId());
                yield movedPlace(r, l != null ? loanGivenRef(l) : description(t, PERSON));
            }
            case FROM_SAVINGS -> {
                Investment h = t.getInvestmentId() == null ? null : tables.holdingsById().get(t.getInvestmentId());
                yield movedPlace(r, h != null ? Ref.of("holding:" + h.getId(), HOLDING, h.getId(), h.getName(), null)
                        : description(t, HOLDING));
            }
        };
    }

    /**
     * Earned money and itemised everyday spending: the line of the category's root, and under it the
     * row's own category — or, a row booked on the root itself, the root's {@code :self} line (kept
     * only when the root has other category lines, {@link Lines#line}).
     */
    private static Place categoryPlace(Part part, Category own, Tables tables, boolean everyday, BigDecimal amount) {
        Category c = tables.category(own);
        if (c == null) return new Place(part, uncategorized(), null, everyday, amount);
        Category root = tables.rootOf(c);
        Ref child = c == root || Objects.equals(c.getId(), root.getId())
                ? new Ref("cat:" + root.getId() + ":self", CATEGORY, root.getId(), root.getName(), root.getNameUz(), null, null)
                : categoryRef(c);
        return new Place(part, categoryRef(root), child, everyday, amount);
    }

    /** Set aside: the group by the row's savings kind, then the donation kind or the holding it paid into. */
    private static Place savedPlace(Row r, Tables tables) {
        Transaction t = r.t();
        String group = switch (r.kind()) {
            case AnalyticsService.DONATION -> "DONATIONS";
            case AnalyticsService.EMERGENCY -> "EMERGENCY";
            case AnalyticsService.GOAL -> "GOALS";
            default -> "INVESTMENTS";
        };
        Ref top = Ref.of("group:" + group, GROUP, null, group, null);
        Ref child;
        if (r.flow() == TransactionFlow.GIVEN) {
            Category c = tables.category(t.getCategory());
            if (c == null) c = tables.donationRoot();
            child = c == null ? description(t, DONATION_KIND)
                    : Ref.of("donation:" + c.getId(), DONATION_KIND, c.getId(), c.getName(), c.getNameUz());
        } else {
            Investment h = tables.holdingOf(t);
            if (h != null) {
                child = "GOALS".equals(group) ? Ref.of("goal:" + h.getId(), GOAL, h.getId(), h.getName(), null)
                        : Ref.of("holding:" + h.getId(), HOLDING, h.getId(), h.getName(), null);
            } else if ("EMERGENCY".equals(group)) {
                child = Ref.of("emergency:none", HOLDING, null, "Emergency fund", null);
            } else if (t.getSubType() == TransactionSubType.STOCK_PURCHASE) {
                child = Ref.of("stocks:none", HOLDING, null, "Stocks", null);
            } else {
                child = Ref.of("holding:?:" + t.getDescription(), HOLDING, t.getInvestmentId(), t.getDescription(), null);
            }
        }
        return new Place(Part.SET_ASIDE, top, child, false, r.amount());
    }

    private static Place movedPlace(Row r, Ref child) {
        TransactionFlow f = r.flow();
        return new Place(Part.MOVED, new Ref("moved:" + f.name(), GROUP, null, f.name(), null, null, f),
                child, false, r.amount());
    }

    /**
     * The loan a payment paid — {@code GET /analytics}' rule (its {@code Details.loanSum}): a bank
     * installment by {@link AnalyticsService#bankLoanOf}, a repayment by the loan or debt it names,
     * else one line per description.
     */
    private static Ref loanRef(Transaction t, Tables tables) {
        if (t.getSubType() == TransactionSubType.BANK_LOAN_PAYMENT) {
            BankLoan b = AnalyticsService.bankLoanOf(t, tables.bankLoans());
            return b == null
                    ? new Ref("loan:BANK:?", LOAN, null, AnalyticsService.BANK_LOAN, null, AnalyticsService.BANK, null)
                    : new Ref("loan:BANK:" + b.getId(), LOAN, b.getId(), DailyAdviceService.bankName(b), null,
                            AnalyticsService.BANK, null);
        }
        if (t.getRepaidLoanTakenId() != null) {
            LoanTaken l = tables.loansTaken().get(t.getRepaidLoanTakenId());
            return new Ref("loan:LOAN:" + t.getRepaidLoanTakenId(), LOAN, t.getRepaidLoanTakenId(),
                    l == null ? t.getDescription() : l.getLenderName(), null, AnalyticsService.LOAN, null);
        }
        if (t.getRepaidDebtId() != null) {
            Debt d = tables.debts().get(t.getRepaidDebtId());
            return new Ref("loan:DEBT:" + t.getRepaidDebtId(), LOAN, t.getRepaidDebtId(),
                    d == null ? t.getDescription() : d.getCreditorName(), null, AnalyticsService.DEBT, null);
        }
        return new Ref("loan:?:" + t.getDescription(), LOAN, null, t.getDescription(), null, AnalyticsService.LOAN, null);
    }

    private static Ref categoryRef(Category c) {
        return Ref.of("cat:" + c.getId(), CATEGORY, c.getId(), c.getName(), c.getNameUz());
    }

    private static Ref uncategorized() {
        return Ref.of(UNCATEGORIZED, CATEGORY, null, AnalyticsService.UNCATEGORIZED, null);
    }

    private static Ref loanGivenRef(LoanGiven l) {
        return Ref.of("loanGiven:" + l.getId(), PERSON, l.getId(), l.getDebtorName(), null);
    }

    private static Ref description(Transaction t, String kind) {
        return Ref.of("desc:" + t.getDescription(), kind, null, t.getDescription(), null);
    }

    /** Two rows of one line describe it alike; the first that names an id behind it wins. */
    private static Ref keepRefId(Ref seen, Ref next) {
        return seen.refId() == null && next.refId() != null ? next : seen;
    }

    // ── The lines (§4.3) ──────────────────────────────────────────────────────

    /** Builds the four lists from the month-by-month sums. */
    private static final class Lines {
        private final Scope scope;
        private final Map<YearMonth, Map<LineId, Sum>> sums;
        private final Map<LineId, Ref> refs;
        /** Every counted row, whatever its month — a loan's last repayment may be before the history start. */
        private final List<Row> rows;
        /** Every counted row by its month, whatever its day: what History's month view lists. */
        private final Map<YearMonth, List<Row>> listed;
        private final Tables tables;
        private final Map<Part, List<Line>> built = new HashMap<>();

        private Lines(Scope scope, Map<YearMonth, Map<LineId, Sum>> sums, Map<LineId, Ref> refs, List<Row> rows,
                      Map<YearMonth, List<Row>> listed, Tables tables) {
            this.scope = scope;
            this.sums = sums;
            this.refs = refs;
            this.rows = rows;
            this.listed = listed;
            this.tables = tables;
        }

        private List<Line> list(Part part) {
            return built.computeIfAbsent(part, this::build);
        }

        private List<Line> build(Part part) {
            Set<String> tops = new LinkedHashSet<>();
            // The group keys are always sent, at 0 when nothing happened.
            switch (part) {
                case OUT -> {
                    always(new LineId(Part.OUT, UNITEMISED, null), Ref.of(UNITEMISED, NOT_ITEMISED, null, "Not itemised", null));
                    always(new LineId(Part.OUT, LOANS, null), Ref.of(LOANS, GROUP, null, "Loan payments", null));
                    tops.add(UNITEMISED);
                    tops.add(LOANS);
                }
                case SET_ASIDE -> {
                    for (String g : SET_ASIDE_GROUPS) {
                        always(new LineId(Part.SET_ASIDE, "group:" + g, null), Ref.of("group:" + g, GROUP, null, g, null));
                        tops.add("group:" + g);
                    }
                }
                case MOVED -> {
                    for (TransactionFlow f : MOVED_FLOWS) {
                        always(new LineId(Part.MOVED, "moved:" + f.name(), null),
                                new Ref("moved:" + f.name(), GROUP, null, f.name(), null, null, f));
                        tops.add("moved:" + f.name());
                    }
                }
                default -> { }
            }
            tops.addAll(keys(part, null));

            List<Line> lines = new ArrayList<>();
            for (String top : tops) lines.add(line(part, top));
            if (part == Part.SET_ASIDE) {
                lines.sort(Comparator.comparingInt(l -> SET_ASIDE_GROUPS.indexOf(l.getName())));
            } else if (part == Part.MOVED) {
                lines.sort(Comparator.comparingInt(l -> MOVED_FLOWS.indexOf(l.getFlow())));
            } else {
                lines.sort(order());
            }
            return lines;
        }

        private void always(LineId id, Ref ref) {
            refs.putIfAbsent(id, ref);
        }

        /**
         * The keys of {@code part}'s lines (top lines when {@code top} is null, else that line's
         * children) that have a row in the range — and, for one month with a base, the lasting ones
         * that had a row in a base month, carried at 0 with their expected.
         */
        private Set<String> keys(Part part, String top) {
            Set<String> keys = new LinkedHashSet<>();
            for (YearMonth m : scope.range()) collect(m, part, top, keys, false);
            if (scope.hasBase()) for (YearMonth m : scope.base()) collect(m, part, top, keys, true);
            return keys;
        }

        private void collect(YearMonth m, Part part, String top, Set<String> keys, boolean lastingOnly) {
            for (Map.Entry<LineId, Sum> e : sums.getOrDefault(m, Map.of()).entrySet()) {
                LineId id = e.getKey();
                if (id.part() != part || e.getValue().rows.isEmpty()) continue;
                String key = top == null ? (id.child() == null ? id.top() : null)
                        : (top.equals(id.top()) ? id.child() : null);
                if (key == null || (lastingOnly && !lasting(key))) continue;
                keys.add(key);
            }
        }

        private Line line(Part part, String top) {
            List<Line> children = new ArrayList<>();
            for (String child : keys(part, top)) children.add(figures(new LineId(part, top, child), List.of()));
            // A root's own rows get a line of their own only beside other category lines (§4.4).
            String self = top + ":self";
            boolean hasCategories = children.stream()
                    .anyMatch(c -> c.getKey().startsWith("cat:") && !c.getKey().equals(self));
            if (!hasCategories) children.removeIf(c -> c.getKey().equals(self));
            if (UNITEMISED.equals(top)) children.sort(Comparator.comparing(Line::getKey));
            else children.sort(order());
            return figures(new LineId(part, top, null), children);
        }

        /** One line's figures over the scope's months. */
        private Line figures(LineId id, List<Line> children) {
            Ref ref = refs.get(id);
            String key = id.child() == null ? id.top() : id.child();
            List<Row> lineRows = new ArrayList<>();
            BigDecimal everyday = BigDecimal.ZERO;
            BigDecimal other = BigDecimal.ZERO;
            List<BigDecimal> byMonth = new ArrayList<>();
            for (YearMonth m : scope.range()) {
                Sum s = sum(m, id);
                if (s == null) {
                    byMonth.add(BigDecimal.ZERO);
                    continue;
                }
                everyday = everyday.add(s.everyday);
                other = other.add(s.other);
                lineRows.addAll(s.rows);
                byMonth.add(s.amount());
            }
            BigDecimal amount = everyday.add(other);
            boolean outCategory = id.part() == Part.OUT && CATEGORY.equals(ref.kind());
            boolean moved = id.part() == Part.MOVED;

            BigDecimal expected = null;
            boolean isNew = false;
            if (scope.hasBase() && !moved && lasting(key)) {
                BigDecimal baseEveryday = BigDecimal.ZERO;
                BigDecimal baseOther = BigDecimal.ZERO;
                boolean seen = false;
                for (YearMonth m : scope.base()) {
                    Sum s = sum(m, id);
                    if (s == null) continue;
                    baseEveryday = baseEveryday.add(s.everyday);
                    baseOther = baseOther.add(s.other);
                    if (s.amount().signum() != 0) seen = true;
                }
                isNew = amount.signum() != 0 && !seen;
                if (!isNew) {
                    int baseDays = scope.base().stream().mapToInt(YearMonth::lengthOfMonth).sum();
                    expected = scaled(baseEveryday, scope.to().lengthOfMonth(), baseDays)
                            .add(averaged(baseOther, scope.base().size()));
                }
            }
            BigDecimal average = null;
            if (!scope.oneMonth() && !moved) {
                List<YearMonth> complete = scope.completeTracked();
                if (!complete.isEmpty()) {
                    BigDecimal sum = BigDecimal.ZERO;
                    for (YearMonth m : complete) {
                        Sum s = sum(m, id);
                        if (s != null) sum = sum.add(s.amount());
                    }
                    average = averaged(sum, complete.size());
                }
            }
            BigDecimal sinceStart = null;
            if (id.part() == Part.SET_ASIDE && id.child() == null) {
                sinceStart = BigDecimal.ZERO;
                for (YearMonth m : sums.keySet()) {
                    Sum s = sum(m, id);
                    if (s != null) sinceStart = sinceStart.add(s.amount());
                }
            }
            boolean closed = LOAN.equals(ref.kind()) && closed(ref);

            return Line.builder()
                    .key(key)
                    .kind(ref.kind())
                    .refId(ref.refId())
                    .name(ref.name())
                    .nameUz(ref.nameUz())
                    .incomeKind(id.part() == Part.INCOME ? incomeKind(id) : null)
                    .loanKind(ref.loanKind())
                    .closed(closed)
                    .flow(ref.flow())
                    .amount(amount)
                    .everyday(outCategory ? everyday : null)
                    .bills(outCategory ? other : null)
                    .count(lineRows.size())
                    .expected(expected)
                    .isNew(isNew)
                    .average(average)
                    .byMonth(scope.oneMonth() ? List.of() : byMonth)
                    .sinceStart(sinceStart)
                    .otherMonth(id.part() == Part.INCOME ? otherMonth(lineRows) : List.of())
                    .top(outCategory && id.child() == null ? top(lineRows) : List.of())
                    .history(scope.oneMonth() ? history(id, ref, lineRows) : null)
                    .children(children)
                    .build();
        }

        private Sum sum(YearMonth m, LineId id) {
            Map<LineId, Sum> month = sums.get(m);
            return month == null ? null : month.get(id);
        }

        /** PAY · BONUS · OTHER when every row of the line (in the scope's months) is one kind; else null. */
        private String incomeKind(LineId id) {
            Set<String> kinds = new TreeSet<>();
            List<YearMonth> months = new ArrayList<>(scope.range());
            if (scope.hasBase()) months.addAll(scope.base());
            for (YearMonth m : months) {
                Sum s = sum(m, id);
                if (s != null) for (Row r : s.rows) kinds.add(r.kind());
            }
            return kinds.size() == 1 ? kinds.iterator().next() : null;
        }

        /** A LOAN or DEBT paid off now whose last repayment was before the line's month (a range: its last). */
        private boolean closed(Ref ref) {
            if (ref.refId() == null) return false;
            RecordStatus status;
            Predicate<Transaction> repays;
            if (AnalyticsService.LOAN.equals(ref.loanKind())) {
                LoanTaken l = tables.loansTaken().get(ref.refId());
                status = l == null ? null : l.getStatus();
                repays = t -> ref.refId().equals(t.getRepaidLoanTakenId());
            } else if (AnalyticsService.DEBT.equals(ref.loanKind())) {
                Debt d = tables.debts().get(ref.refId());
                status = d == null ? null : d.getStatus();
                repays = t -> ref.refId().equals(t.getRepaidDebtId());
            } else {
                return false;
            }
            if (status != RecordStatus.PAID) return false;
            LocalDate last = null;
            for (Row r : rows) {
                if (r.cls() == FlowClass.LOAN_PAYMENT && repays.test(r.t())
                        && (last == null || r.date().isAfter(last))) last = r.date();
            }
            return last != null && last.isBefore(scope.to().atDay(1));
        }

        /** Rows counted here whose day is in another month (a range: outside it), summed by day. */
        private List<OtherMonth> otherMonth(List<Row> rows) {
            Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
            for (Row r : rows) {
                if (within(YearMonth.from(r.date()), scope.from(), scope.to())) continue;
                byDay.merge(r.date(), r.amount(), BigDecimal::add);
            }
            List<OtherMonth> out = new ArrayList<>();
            byDay.forEach((d, a) -> out.add(OtherMonth.builder().date(d).amount(a).build()));
            return out;
        }

        /** The largest itemised everyday rows, latest first among equals. */
        private static List<TopRow> top(List<Row> rows) {
            List<Row> itemised = new ArrayList<>(rows.stream().filter(r -> r.cls() == FlowClass.EVERYDAY).toList());
            itemised.sort(Comparator.comparing(Row::amount).reversed()
                    .thenComparing(Row::date, Comparator.reverseOrder()));
            List<TopRow> out = new ArrayList<>();
            for (Row r : itemised.size() > TOP_ROWS ? itemised.subList(0, TOP_ROWS) : itemised) {
                out.add(TopRow.builder().id(r.t().getId()).date(r.date()).description(r.t().getDescription())
                        .amount(r.amount()).build());
            }
            return out;
        }

        /**
         * The first History filter that lists exactly this line's rows of the month — every one of
         * them, and no other row the month counts, a row dated after today included; null when none
         * can (a holding's creating row carries no investmentId, a root's own rows cannot be told
         * from its children's, a bill paid ahead for its day is not in the line yet, …).
         */
        private LineHistory history(LineId id, Ref ref, List<Row> rows) {
            return exactly(candidates(id, ref, rows), rows, listed.getOrDefault(scope.to(), List.of()), tables);
        }

        private List<LineHistory> candidates(LineId id, Ref ref, List<Row> rows) {
            String key = id.child() == null ? id.top() : id.child();
            Long refId = ref.refId();
            if (key.startsWith("moved:")) return List.of(flow(ref.flow().name()));
            if (key.startsWith("group:")) return List.of(flow(key.equals("group:DONATIONS") ? "GIVEN" : "SAVED"));
            if (key.equals(UNITEMISED)) return List.of(LineHistory.builder().walletCheck(true).build());
            if (key.startsWith("check:")) {
                LocalDate day = LocalDate.parse(key.substring("check:".length()));
                return List.of(LineHistory.builder().walletCheck(true).from(day).to(day).build());
            }
            if (key.equals(LOANS) || (key.startsWith("loan:") && refId != null)) return List.of(flow("LOAN_PAYMENT"));
            if (key.startsWith("bill:")) {
                Ref top = refs.get(new LineId(id.part(), id.top(), null));
                return top == null || top.refId() == null || !CATEGORY.equals(top.kind()) ? List.of()
                        : List.of(LineHistory.builder().flow("BILL").categoryId(top.refId()).build());
            }
            if (key.startsWith("cat:") && !key.endsWith(":self")) {
                LineHistory plain = LineHistory.builder().categoryId(refId).build();
                return id.part() == Part.OUT
                        ? List.of(plain, LineHistory.builder().categoryId(refId).flow("EVERYDAY").build())
                        : List.of(plain);
            }
            if ((key.startsWith("holding:") || key.startsWith("goal:")) && !key.contains("?")) {
                String flow = id.part() == Part.MOVED ? "FROM_SAVINGS" : "SAVED";
                return List.of(LineHistory.builder().investmentId(refId).build(),
                        LineHistory.builder().investmentId(refId).flow(flow).build());
            }
            if (key.startsWith("donation:")) {
                return List.of(LineHistory.builder().categoryId(refId).build(),
                        LineHistory.builder().categoryId(refId).flow("GIVEN").build());
            }
            if (key.equals("emergency:none") || key.equals("stocks:none")) {
                Set<Long> categories = new TreeSet<>();
                for (Row r : rows) {
                    Category c = tables.category(r.t().getCategory());
                    categories.add(c == null ? -1L : c.getId());
                }
                return categories.size() == 1 && !categories.contains(-1L)
                        ? List.of(LineHistory.builder().flow("SAVED").categoryId(categories.iterator().next()).build())
                        : List.of();
            }
            return List.of(); // :self, uncategorized, desc:, people, and every key with "?"
        }

        /**
         * With a base: by expected (a new line, or a loan paid off before the month, by its amount),
         * largest first, then by amount; without one — or over a range — by amount. Ties by name, then
         * key, so the order is stable.
         */
        private Comparator<Line> order() {
            Function<Line, BigDecimal> rank = scope.hasBase()
                    ? l -> Boolean.TRUE.equals(l.getIsNew()) || l.isClosed() || l.getExpected() == null
                            ? l.getAmount() : l.getExpected()
                    : Line::getAmount;
            return Comparator.comparing(rank, Comparator.reverseOrder())
                    .thenComparing(Line::getAmount, Comparator.reverseOrder())
                    .thenComparing(l -> l.getName() == null ? "" : l.getName())
                    .thenComparing(Line::getKey);
        }

        private static LineHistory flow(String flow) {
            return LineHistory.builder().flow(flow).build();
        }
    }

    // ── Links to History ──────────────────────────────────────────────────────

    /**
     * The first of {@code candidates} that lists exactly {@code own} among {@code listed} (every row
     * History's month view lists for the month): all of {@code own}, and no other; null when none does.
     */
    private static LineHistory exactly(List<LineHistory> candidates, List<Row> own, List<Row> listed, Tables tables) {
        for (LineHistory h : candidates) {
            if (!own.stream().allMatch(r -> matches(h, r, tables))) continue;
            if (listed.stream().filter(r -> matches(h, r, tables)).count() == own.size()) return h;
        }
        return null;
    }

    /** Whether History, so filtered, lists the row — its category filter also takes a root's children. */
    private static boolean matches(LineHistory h, Row r, Tables tables) {
        Transaction t = r.t();
        if (h.getCategoryId() != null) {
            Category c = tables.category(t.getCategory());
            if (c == null) return false;
            Long parent = c.getParent() == null ? null : c.getParent().getId();
            if (!h.getCategoryId().equals(c.getId()) && !h.getCategoryId().equals(parent)) return false;
        }
        if (h.getInvestmentId() != null && !h.getInvestmentId().equals(t.getInvestmentId())) return false;
        if (h.getFlow() != null && !List.of(h.getFlow().split(",")).contains(r.flow().name())) return false;
        if (Boolean.TRUE.equals(h.getWalletCheck()) && t.getSubType() != TransactionSubType.EVERYDAY_SPENDING) return false;
        if (h.getFrom() != null && r.date().isBefore(h.getFrom())) return false;
        return h.getTo() == null || !r.date().isAfter(h.getTo());
    }

    /**
     * A key that keeps meaning from month to month, so it is carried into a month that has no row for
     * it: never one tied to a day or an entry (check:, desc:, any "?" key) or to a person.
     */
    static boolean lasting(String key) {
        return !key.contains("?") && !key.startsWith("check:") && !key.startsWith("desc:")
                && !key.startsWith("loanTaken:") && !key.startsWith("loanGiven:") && !key.startsWith("debt:");
    }

    // ── Expected and average: other months' figures, rounded once ─────────────

    /**
     * The months' flow averaged: everyday spending scaled by days when {@code days} > 0
     * (Σ × days ÷ Σ the months' days — an expected month), else Σ ÷ the number of months (an average
     * month); every figure rounded once, out and left over worked out from the rounded parts.
     */
    private static Flow estimate(List<Flow> months, int days, int monthsDays) {
        int n = months.size();
        Function<Function<Flow, BigDecimal>, BigDecimal> avg = field -> averaged(sum(months, field), n);
        Function<Function<Flow, BigDecimal>, BigDecimal> everyday = field -> days > 0
                ? scaled(sum(months, field), days, monthsDays) : averaged(sum(months, field), n);
        return new Estimate(avg.apply(Flow::getEarned), avg.apply(Flow::getEarnedPay), avg.apply(Flow::getEarnedBonus),
                avg.apply(Flow::getEarnedOther), everyday.apply(Flow::getEveryday),
                everyday.apply(Flow::getEverydayUnitemised), avg.apply(Flow::getBills), avg.apply(Flow::getLoanPayments),
                avg.apply(Flow::getSaved), avg.apply(Flow::getSavedDonation), avg.apply(Flow::getSavedEmergency),
                avg.apply(Flow::getSavedInvestments), avg.apply(Flow::getSavedGoals), avg.apply(Flow::getBorrowed),
                avg.apply(Flow::getLent), avg.apply(Flow::getReturned), avg.apply(Flow::getFromSavings));
    }

    private static BigDecimal sum(List<Flow> months, Function<Flow, BigDecimal> field) {
        BigDecimal s = BigDecimal.ZERO;
        for (Flow f : months) s = s.add(field.apply(f));
        return s;
    }

    /** Σ × days ÷ the base months' days, rounded once, half-up, to the so'm. */
    static BigDecimal scaled(BigDecimal sum, int days, int baseDays) {
        if (baseDays == 0) return BigDecimal.ZERO;
        return sum.multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(baseDays), 0, RoundingMode.HALF_UP);
    }

    /** Σ ÷ n, rounded once, half-up, to the so'm. */
    static BigDecimal averaged(BigDecimal sum, int n) {
        if (n == 0) return BigDecimal.ZERO;
        return sum.divide(BigDecimal.valueOf(n), 0, RoundingMode.HALF_UP);
    }

    /** Itemised everyday spending day by day, for a one-month range from the history start on. */
    private static List<DayEveryday> everydayDaily(Scope scope, Map<YearMonth, List<Row>> rowsByMonth) {
        YearMonth m = scope.to();
        if (!scope.oneMonth() || scope.start() == null || m.isBefore(scope.start())) return List.of();
        int last = m.equals(scope.current()) ? scope.date().getDayOfMonth() : m.lengthOfMonth();
        BigDecimal[] byDay = new BigDecimal[last];
        java.util.Arrays.fill(byDay, BigDecimal.ZERO);
        for (Row r : rowsByMonth.getOrDefault(m, List.of())) {
            if (r.cls() != FlowClass.EVERYDAY || !YearMonth.from(r.date()).equals(m)) continue;
            int d = r.date().getDayOfMonth() - 1;
            if (d < last) byDay[d] = byDay[d].add(r.amount());
        }
        List<DayEveryday> out = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < last; i++) {
            running = running.add(byDay[i]);
            out.add(DayEveryday.builder().day(i + 1).amount(byDay[i]).cumulative(running).build());
        }
        return out;
    }

    // ── Goals: put in, taken out, reached and asked, month by month (§3.5, §4.3) ──

    private List<Goal> goals(Scope scope, List<Row> rows, Map<YearMonth, List<Row>> listed, List<Transaction> all,
                             Tables tables) {
        // The savings goals, and any holding that took goal money up to `to` but is no goal now: Set
        // aside's Goals group goes by the bucket each row was saved with, so it still lists that
        // money, and put in must too (§3.5: the two never disagree, whichever way the flag was edited).
        Set<Long> tookGoalMoney = new HashSet<>();
        for (Row r : rows) {
            // Only goal money inside the viewed range: a holding no longer a goal leaves the Goals page
            // once its goal months are outside the range, and Σ putIn still equals the Goals group.
            if (!isGoalMoney(r) || !within(r.month(), scope.from(), scope.to())) continue;
            Investment h = tables.holdingOf(r.t());
            if (h != null) tookGoalMoney.add(h.getId());
        }
        List<Investment> goals = tables.holdings().stream()
                .filter(h -> h.getId() != null && (h.getCurrency() == null || h.getCurrency() == Currency.UZS)
                        && (Boolean.TRUE.equals(h.getSavingsGoal()) || tookGoalMoney.contains(h.getId())))
                .toList();
        if (goals.isEmpty()) return List.of();
        Map<Long, Map<YearMonth, HoldingMonthSnapshot>> snapshots = new HashMap<>();
        for (HoldingMonthSnapshot s : orEmpty(snapshotRepository.findByInvestmentIdIn(
                goals.stream().map(Investment::getId).toList()))) {
            snapshots.computeIfAbsent(s.getInvestmentId(), k -> new HashMap<>()).put(YearMonth.from(s.getMonth()), s);
        }

        List<Goal> out = new ArrayList<>();
        for (Investment g : goals) {
            Goal goal = goal(g, scope, rows, listed, all, tables, snapshots.getOrDefault(g.getId(), Map.of()));
            if (goal != null) out.add(goal);
        }
        // Plans by deadline (soonest first, none last), then wishes; ties by name.
        out.sort(Comparator.comparing((Goal g) -> Investment.WISH.equals(g.getKind()))
                .thenComparing(g -> g.getDeadline() == null ? "9999-99" : g.getDeadline())
                .thenComparing(g -> g.getName() == null ? "" : g.getName())
                .thenComparing(Goal::getRefId));
        return out;
    }

    /** A row of the Goals group on Set aside: saved money whose bucket made it goal money. */
    private static boolean isGoalMoney(Row r) {
        return r.cls() == FlowClass.SAVED && AnalyticsService.GOAL.equals(r.kind());
    }

    /** One goal, or null when it did not exist yet by the end of {@code to} and had no row by then. */
    private Goal goal(Investment g, Scope scope, List<Row> rows, Map<YearMonth, List<Row>> listed,
                      List<Transaction> all, Tables tables, Map<YearMonth, HoldingMonthSnapshot> snapshots) {
        // Put in: the goal's rows of the Goals group (the Set aside page's rule); taken out: its withdrawals.
        Map<YearMonth, BigDecimal> putIn = new TreeMap<>();
        Map<YearMonth, BigDecimal> takenOut = new TreeMap<>();
        Map<YearMonth, List<Row>> own = new HashMap<>();
        BigDecimal putInTotal = BigDecimal.ZERO;
        BigDecimal takenOutTotal = BigDecimal.ZERO;
        YearMonth firstRow = null;
        Row creating = null;
        for (Row r : rows) {
            Transaction t = r.t();
            boolean in = isGoalMoney(r) && tables.holdingOf(t) == g;
            boolean out = HoldingLinks.takenOut(t, g);
            if (t.getId() != null && t.getId().equals(g.getOriginatingTransactionId())) creating = r;
            if (in) {
                putIn.merge(r.month(), r.amount(), BigDecimal::add);
                putInTotal = putInTotal.add(r.amount());
            }
            if (out) {
                takenOut.merge(r.month(), r.amount(), BigDecimal::add);
                takenOutTotal = takenOutTotal.add(r.amount());
            }
            if (in || out) own.computeIfAbsent(r.month(), k -> new ArrayList<>()).add(r);
            if ((in || out || HoldingLinks.linked(t, g)) && !r.month().isAfter(scope.to())) firstRow = earlier(firstRow, r.month());
        }
        YearMonth created = g.getPurchaseDate() == null ? null : YearMonth.from(g.getPurchaseDate());
        boolean exists = created != null && !created.isAfter(scope.to());
        if (!exists && firstRow == null) return null;

        // Its months: from the later of the history start and the goal's first month, the last 24 at most.
        YearMonth first = earlier(created, firstRow);
        if (first == null || first.isAfter(scope.to())) first = scope.to();
        if (scope.start() != null && scope.start().isAfter(first)) first = scope.start();
        if (ChronoUnit.MONTHS.between(first, scope.to()) >= GOAL_MONTHS) first = scope.to().minusMonths(GOAL_MONTHS - 1);

        Reached reached = new Reached(g, all, scope, snapshots);
        YearMonth creatingMonth = creating == null ? null : creating.month();
        List<GoalMonth> months = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(scope.to()); m = m.plusMonths(1)) {
            Reached.Value end = reached.at(m);
            BigDecimal in = putIn.getOrDefault(m, BigDecimal.ZERO);
            BigDecimal outM = takenOut.getOrDefault(m, BigDecimal.ZERO);
            months.add(GoalMonth.builder()
                    .month(m.toString())
                    .putIn(in)
                    .takenOut(outM)
                    .net(in.subtract(outM))
                    .asked(asked(g, m, scope, snapshots.get(m), reached))
                    .reachedEnd(end.value())
                    .approximate(end.approximate())
                    .fromSnapshot(end.fromSnapshot())
                    .history(m.equals(creatingMonth) ? null : goalHistory(g, own.getOrDefault(m, List.of()),
                            listed.getOrDefault(m, List.of()), tables))
                    .build());
        }
        YearMonth lastPutIn = null;
        for (Map.Entry<YearMonth, BigDecimal> e : putIn.entrySet()) {
            if (!e.getKey().isAfter(scope.to()) && e.getValue().signum() > 0) lastPutIn = e.getKey();
        }

        // A holding that is no goal now (listed for its goal money) is a wish: it asks nothing, but a
        // past month whose snapshot noted a plan (it was a goal then) asks by that plan.
        String kind = g.goalKind() != null ? g.goalKind() : Investment.WISH;
        return Goal.builder()
                .refId(g.getId())
                .name(g.getName())
                .kind(kind)
                .target(g.getTargetAmount())
                .deadline(g.getTargetDate() == null ? null : YearMonth.from(g.getTargetDate()).toString())
                .monthly(Investment.PLAN.equals(kind) ? g.getMonthlyContribution() : null)
                .startMonth(text(g.paymentStartMonth()))
                .createdMonth(text(created))
                .valueNow(AdvisorService.value(g))
                .valueTracked(HoldingLinks.valueTracked(g))
                .putInTotal(putInTotal)
                .takenOutTotal(takenOutTotal)
                .lastPutIn(text(lastPutIn))
                .months(months)
                .build();
    }

    /**
     * The goal card's link to History for a month: its put-in and taken-out rows ({@code own}), and
     * no other row History's month view lists — by the goal's id, else by its id and SAVED; null when
     * neither lists exactly them (a row of another bucket into the goal in the same month, …).
     */
    private static LineHistory goalHistory(Investment g, List<Row> own, List<Row> listed, Tables tables) {
        return exactly(List.of(LineHistory.builder().investmentId(g.getId()).build(),
                LineHistory.builder().investmentId(g.getId()).flow(TransactionFlow.SAVED.name()).build()),
                own, listed, tables);
    }

    /**
     * What a plan asked in a month: its monthly payment, capped at what was still missing at the end
     * of the month before; a plan without a target asks its payment. A complete month asks by the
     * plan noted in its snapshot, when it has one; else by the plan now. A wish, a holding that is
     * no goal now (unless its snapshot noted a plan), or a month before the payment starts, asks
     * nothing (null).
     */
    private static BigDecimal asked(Investment g, YearMonth m, Scope scope, HoldingMonthSnapshot snapshot,
                                    Reached reached) {
        boolean noted = snapshot != null && m.isBefore(scope.current());
        boolean wish = noted ? snapshot.isWish() : !Investment.PLAN.equals(g.goalKind());
        BigDecimal monthly = noted ? snapshot.getMonthly() : g.getMonthlyContribution();
        BigDecimal target = noted ? snapshot.getTarget() : g.getTargetAmount();
        if (wish || monthly == null || monthly.signum() <= 0) return null;
        YearMonth starts = g.paymentStartMonth();
        if (starts != null && m.isBefore(starts)) return null;
        if (target == null || target.signum() <= 0) return monthly;
        BigDecimal missing = target.subtract(reached.at(m.minusMonths(1)).value()).max(BigDecimal.ZERO);
        return monthly.min(missing);
    }

    /**
     * What a goal had reached at the end of a month (§4.3 {@code reachedEnd}): now, for the month in
     * progress; else rebuilt from its value now and the rows dated since, when every so'm has a row;
     * else from the month's snapshot, corrected for rows back-dated into the month after it was
     * taken; else rebuilt all the same, approximately.
     */
    private static final class Reached {
        private record Value(BigDecimal value, boolean approximate, boolean fromSnapshot) { }

        private final Investment goal;
        private final List<Transaction> rows;
        private final Scope scope;
        private final Map<YearMonth, HoldingMonthSnapshot> snapshots;
        private final boolean exact;
        private final Map<YearMonth, Value> memo = new HashMap<>();

        private Reached(Investment goal, List<Transaction> all, Scope scope, Map<YearMonth, HoldingMonthSnapshot> snapshots) {
            this.goal = goal;
            this.rows = all.stream().filter(t -> HoldingLinks.linked(t, goal)).toList();
            this.scope = scope;
            this.snapshots = snapshots;
            this.exact = HoldingLinks.exact(rows, goal);
        }

        private Value at(YearMonth m) {
            return memo.computeIfAbsent(m, this::compute);
        }

        private Value compute(YearMonth m) {
            if (!m.isBefore(scope.current())) return new Value(AdvisorService.value(goal), false, false);
            BigDecimal rebuilt = HoldingLinks.rebuiltValue(rows, goal, m);
            if (exact) return new Value(rebuilt, false, false);
            HoldingMonthSnapshot s = snapshots.get(m);
            if (s == null) return new Value(rebuilt, true, false);
            BigDecimal noted = nz(s.getPutInMonth()).subtract(nz(s.getTakenOutMonth()));
            BigDecimal value = nz(s.getValue()).add(HoldingLinks.netIn(rows, goal, m).subtract(noted));
            return new Value(value, !HoldingMonthSnapshot.CLOSING.equals(s.getSource()), true);
        }
    }

    // ── Holdings: what each is worth now (§4.3) ───────────────────────────────

    private static List<Holding> holdingsNow(LocalDate date, List<Row> rows, Tables tables) {
        List<Holding> out = new ArrayList<>();
        for (Investment h : tables.holdings()) {
            if (h.getCurrency() != null && h.getCurrency() != Currency.UZS) continue;
            out.add(Holding.builder().refId(h.getId()).name(h.getName()).kind(HoldingLinks.kindOf(h))
                    .value(AdvisorService.value(h)).putIn(nz(h.getInvestedAmount()))
                    .valueTracked(HoldingLinks.valueTracked(h)).openingBalance(Boolean.TRUE.equals(h.getOpeningBalance()))
                    .build());
        }
        // Emergency money kept in no holding: its contributions, whichever page they were made on.
        BigDecimal bare = BigDecimal.ZERO;
        for (Row r : rows) {
            if (r.t().getSubType() != TransactionSubType.EMERGENCY_CONTRIBUTION || r.cls() != FlowClass.SAVED) continue;
            if (r.date().isAfter(date) || tables.holdingOf(r.t()) != null) continue;
            bare = bare.add(r.amount());
        }
        if (bare.signum() != 0) {
            out.add(Holding.builder().refId(null).name("Emergency fund").kind("EMERGENCY").value(bare).putIn(bare)
                    .valueTracked(false).openingBalance(false).build());
        }
        out.sort(Comparator.comparing(Holding::getValue, Comparator.reverseOrder())
                .thenComparing(h -> h.getName() == null ? "" : h.getName()));
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean within(YearMonth month, YearMonth from, YearMonth to) {
        return !month.isBefore(from) && !month.isAfter(to);
    }

    private static YearMonth earlier(YearMonth a, YearMonth b) {
        if (a == null) return b;
        return b == null || a.isBefore(b) ? a : b;
    }

    private static YearMonth later(YearMonth a, YearMonth b) {
        if (a == null) return b;
        return b == null || a.isAfter(b) ? a : b;
    }

    private static String text(YearMonth m) {
        return m == null ? null : m.toString();
    }

    private static <T> List<T> orEmpty(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private static <T> Map<Long, T> byKey(Collection<T> rows, Function<T, Long> key) {
        Map<Long, T> out = new LinkedHashMap<>();
        for (T row : rows) {
            Long k = key.apply(row);
            if (k != null) out.put(k, row);
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
