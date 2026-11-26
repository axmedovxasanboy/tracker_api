package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.InvestmentSplitRequest;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.SavingsRow;
import uz.tracker.trackerproject.dto.response.InvestmentSplitResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * "This month's split" (Investments page, Batch 81): which investment account each part of the
 * month's Investments money goes to. The owner: "Default shu 1 mln barcha hisobga teng taqsimlanadi.
 * Va men bu taqsimlanishni o'zgartira olishim kerak."
 *
 * <p>The split is a standing share per account ({@link Investment#getSplitWeight}), so it carries to
 * the next month and scales with that month's figure. It changes nothing about how the month's
 * Investments figure is computed — the figure is the advisor's own Investments row (rule amount +
 * carried), the same one Savings shows — it only says which account each part goes to.
 */
@Service
@RequiredArgsConstructor
public class InvestmentSplitService {

    /** Amounts are split in whole 1.000 UZS. */
    static final BigDecimal UNIT = new BigDecimal("1000");

    private final InvestmentRepository investmentRepository;
    private final TransactionRepository transactionRepository;
    private final AdvisorService advisorService;
    private final OverviewService overviewService;

    @Transactional(readOnly = true)
    public InvestmentSplitResponse get(String month) {
        return build(month == null || month.isBlank() ? YearMonth.from(today()) : parseMonth(month));
    }

    /** The typed amounts become the accounts' weights; every plain account not listed gets 0. */
    @Transactional
    public InvestmentSplitResponse put(InvestmentSplitRequest req) {
        List<InvestmentSplitRequest.Weight> given = req == null || req.getWeights() == null ? List.of() : req.getWeights();
        Map<Long, BigDecimal> weights = new LinkedHashMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (InvestmentSplitRequest.Weight w : given) {
            if (w == null || w.getInvestmentId() == null) throw new IllegalArgumentException("Choose an account for each share.");
            Investment i = investmentRepository.findById(w.getInvestmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Investment", w.getInvestmentId()));
            if (!isAccount(i)) throw new IllegalArgumentException(i.getName() + " is not an investment account.");
            if (w.getWeight() == null || w.getWeight().signum() < 0) {
                throw new IllegalArgumentException("A share cannot be negative.");
            }
            BigDecimal before = weights.put(i.getId(), w.getWeight());
            if (before != null) sum = sum.subtract(before);
            sum = sum.add(w.getWeight());
        }
        if (sum.signum() <= 0) throw new IllegalArgumentException("Give at least one account a share.");
        for (Investment i : accounts()) {
            i.setSplitWeight(weights.getOrDefault(i.getId(), BigDecimal.ZERO));
            investmentRepository.save(i);
        }
        return build(YearMonth.from(today()));
    }

    /** Back to an equal split. */
    @Transactional
    public InvestmentSplitResponse reset() {
        for (Investment i : investmentRepository.findAll()) {
            if (i.getSplitWeight() == null) continue;
            i.setSplitWeight(null);
            investmentRepository.save(i);
        }
        return build(YearMonth.from(today()));
    }

    // ── The split ──────────────────────────────────────────────────────────────

    InvestmentSplitResponse build(YearMonth month) {
        List<Investment> accounts = accounts();
        accounts.sort(Comparator.comparing((Investment i) -> provider(i).toLowerCase(Locale.ROOT))
                .thenComparing(i -> i.getName() == null ? "" : i.getName().toLowerCase(Locale.ROOT))
                .thenComparing(Investment::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        MonthFigure figure = figure(month);
        BigDecimal target = figure.target();

        boolean custom = accounts.stream().anyMatch(i -> i.getSplitWeight() != null);
        List<BigDecimal> weights = new ArrayList<>();
        for (Investment i : accounts) weights.add(custom ? nz(i.getSplitWeight()) : BigDecimal.ONE);
        BigDecimal total = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0 && !accounts.isEmpty()) {
            // Every weighted account is gone: an equal split again rather than nothing anywhere.
            custom = false;
            weights.replaceAll(w -> BigDecimal.ONE);
            total = BigDecimal.valueOf(accounts.size());
        }

        List<BigDecimal> amounts = split(target, accounts, weights, total);
        Map<Long, BigDecimal> paidBy = paidByAccount(month, accounts);

        List<InvestmentSplitResponse.Row> rows = new ArrayList<>();
        BigDecimal rowsPaid = BigDecimal.ZERO;
        for (int k = 0; k < accounts.size(); k++) {
            Investment i = accounts.get(k);
            BigDecimal paid = paidBy.getOrDefault(i.getId(), BigDecimal.ZERO);
            rowsPaid = rowsPaid.add(paid);
            BigDecimal amount = amounts.get(k);
            rows.add(InvestmentSplitResponse.Row.builder()
                    .investmentId(i.getId()).name(i.getName()).broker(i.getBroker()).type(i.getType())
                    .percent(weights.get(k).multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP))
                    .amount(amount).paid(paid).left(clampZero(amount.subtract(paid)))
                    .build());
        }
        BigDecimal paid = figure.paid();
        return InvestmentSplitResponse.builder()
                .month(month.toString())
                .target(target)
                .paid(paid)
                .paidOutside(clampZero(paid.subtract(rowsPaid)))
                .left(clampZero(target.subtract(paid)))
                .custom(custom)
                .rows(rows)
                .build();
    }

    /**
     * {@code target} split by weight in whole 1.000s with the largest-remainder method — ties go to
     * the account worth more, then the lower id — so the parts add up to it exactly; a target that is
     * not a multiple of 1.000 puts the leftover (&lt; 1.000) on the first account with a share.
     */
    static List<BigDecimal> split(BigDecimal target, List<Investment> accounts, List<BigDecimal> weights, BigDecimal total) {
        int n = accounts.size();
        List<BigDecimal> out = new ArrayList<>(Collections.nCopies(n, BigDecimal.ZERO));
        if (n == 0 || target == null || target.signum() <= 0 || total.signum() <= 0) return out;
        BigDecimal units = target.divide(UNIT, 0, RoundingMode.FLOOR);
        BigDecimal leftover = target.subtract(units.multiply(UNIT));

        long[] base = new long[n];
        BigDecimal[] frac = new BigDecimal[n];
        long given = 0;
        for (int k = 0; k < n; k++) {
            BigDecimal quota = units.multiply(weights.get(k)).divide(total, 12, RoundingMode.HALF_UP);
            BigDecimal whole = quota.setScale(0, RoundingMode.FLOOR);
            base[k] = whole.longValueExact();
            frac[k] = quota.subtract(whole);
            given += base[k];
        }
        long rest = units.longValueExact() - given;
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < n; k++) order.add(k);
        order.sort((a, b) -> {
            int c = frac[b].compareTo(frac[a]);
            if (c != 0) return c;
            c = valueOf(accounts.get(b)).compareTo(valueOf(accounts.get(a)));
            if (c != 0) return c;
            return Comparator.<Long>nullsLast(Comparator.naturalOrder())
                    .compare(accounts.get(a).getId(), accounts.get(b).getId());
        });
        for (int r = 0; r < rest && r < n; r++) base[order.get(r)]++;

        for (int k = 0; k < n; k++) out.set(k, UNIT.multiply(BigDecimal.valueOf(base[k])));
        if (leftover.signum() > 0) {
            int first = 0;
            for (int k = 0; k < n; k++) if (weights.get(k).signum() > 0) { first = k; break; }
            out.set(first, out.get(first).add(leftover));
        }
        return out;
    }

    /**
     * The month's Investments figure and what was paid into it — the advisor's own Investments row
     * ({@link AdvisorService#savingsThisMonth}): target + carried, as Savings shows "paid of due". For
     * today's month it is read as of today, a past month as of its last day, a later one as of its 1st.
     * No row (nothing asked, nothing carried, or no income) → 0, with paid from the bucket itself.
     */
    MonthFigure figure(YearMonth month) {
        LocalDate today = today();
        YearMonth now = YearMonth.from(today);
        LocalDate date = month.equals(now) ? today : month.isBefore(now) ? month.atEndOfMonth() : month.atDay(1);
        List<SavingsRow> rows = advisorService.savingsThisMonth(date);
        for (SavingsRow r : rows == null ? List.<SavingsRow>of() : rows) {
            if (AllocationBucket.INVESTMENTS.equals(r.getBucket())) {
                return new MonthFigure(nz(r.getTarget()).add(nz(r.getCarried())), nz(r.getPaid()));
            }
        }
        OverviewService.BucketPaid paid = overviewService.computePaidThisMonth(month, Currency.UZS);
        return new MonthFigure(BigDecimal.ZERO, paid == null ? BigDecimal.ZERO : nz(paid.investments()));
    }

    record MonthFigure(BigDecimal target, BigDecimal paid) {}

    /**
     * What went into each account this month for the Investments bucket: the INVESTMENT transactions
     * the bucket counts ({@link OverviewService#bucketOf}), by the holding they fund — a contribution
     * names it by {@code investmentId}, the row that created it by the holding's
     * {@code originatingTransactionId}. Take-outs are not subtracted, as in the bucket.
     */
    private Map<Long, BigDecimal> paidByAccount(YearMonth month, List<Investment> accounts) {
        Map<Long, Long> byOrigin = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        for (Investment i : accounts) {
            ids.add(i.getId());
            if (i.getOriginatingTransactionId() != null) byOrigin.put(i.getOriginatingTransactionId(), i.getId());
        }
        Map<Long, BigDecimal> out = new HashMap<>();
        List<Transaction> txs = transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                TransactionSubType.INVESTMENT, month.atDay(1), month.atEndOfMonth());
        for (Transaction t : txs == null ? List.<Transaction>of() : txs) {
            if (t.getAmount() == null || t.getAmount().signum() <= 0) continue;
            if (!AllocationBucket.INVESTMENTS.equals(overviewService.bucketOf(t))) continue;
            Long id = t.getInvestmentId() != null && ids.contains(t.getInvestmentId())
                    ? t.getInvestmentId() : byOrigin.get(t.getId());
            if (id == null) continue;
            out.merge(id, t.getAmount(), BigDecimal::add);
        }
        return out;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** The page's accounts: plain investments in UZS. */
    private List<Investment> accounts() {
        List<Investment> out = new ArrayList<>();
        for (Investment i : investmentRepository.findAll()) if (isAccount(i)) out.add(i);
        return out;
    }

    static boolean isAccount(Investment i) {
        return InvestmentTransferService.isPlain(i) && (i.getCurrency() == null || i.getCurrency() == Currency.UZS);
    }

    private static String provider(Investment i) {
        if (i.getBroker() != null && !i.getBroker().isBlank()) return i.getBroker().trim();
        return i.getName() == null ? "" : i.getName().trim();
    }

    private static BigDecimal valueOf(Investment i) {
        return nz(i.getCurrentValue() != null ? i.getCurrentValue() : i.getInvestedAmount());
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be YYYY-MM");
        }
    }

    LocalDate today() {
        return LocalDate.now(InvestmentTransferService.OWNER_ZONE);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static BigDecimal clampZero(BigDecimal v) { return v.signum() < 0 ? BigDecimal.ZERO : v; }
}
