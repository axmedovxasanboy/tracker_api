package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Position;
import uz.tracker.trackerproject.entity.HoldingMonthSnapshot;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.repository.EmergencyRepository;
import uz.tracker.trackerproject.repository.HoldingMonthSnapshotRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.PositionSnapshotRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Notes down, once a day, what every holding is worth and what each goal's plan is
 * ({@link HoldingMonthSnapshot}), and keeps the monthly position record going
 * ({@code PositionSnapshot}) — the history that cannot be rebuilt later (ANALYTICS-V2-SPEC §4.6).
 * Run by {@link SnapshotScheduler}; each of the two writes is a transaction of its own, so a failure
 * of one costs only that day's note of it.
 *
 * <p>For each UZS holding: this month's row is rewritten every day (DAILY). On the 1st, first, the
 * month just ended gets its CLOSING row with the same current values (at 00:15 they are the close of
 * its last day), replacing its DAILY row; nothing ever overwrites a CLOSING row. Then, every run, a
 * month just ended that has no row at all (the run on the 1st was missed and no DAILY row was written
 * in it) gets a REBUILT one: the value now less what its rows did since. A catch-up never overwrites
 * a row — a DAILY row from before an outage is nearer the truth than a rebuild. Older gaps are left.
 * Running twice writes the same rows.
 */
@Service
@RequiredArgsConstructor
public class HoldingSnapshotService {

    private final InvestmentRepository investmentRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingMonthSnapshotRepository snapshotRepository;
    private final PositionSnapshotService positionSnapshotService;
    private final PositionSnapshotRepository positionSnapshotRepository;
    private final MonthCloseService monthCloseService;
    private final EmergencyRepository emergencyRepository;
    private final OverviewService overviewService;
    private final LoanGivenRepository loanGivenRepository;

    /** Every holding's row for {@code today}'s month, the closing row on the 1st, and the catch-up. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordHoldings(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        YearMonth previous = month.minusMonths(1);
        for (Investment h : uzsHoldings()) {
            List<Transaction> rows = linkedRows(h);
            // The month just ended: its closing picture on the 1st, else a catch-up where it has no row.
            if (existedBy(h, previous)) {
                HoldingMonthSnapshot closed = snapshotRepository.findByInvestmentIdAndMonth(h.getId(), previous.atDay(1)).orElse(null);
                if (today.getDayOfMonth() == 1) {
                    if (closed == null || !HoldingMonthSnapshot.CLOSING.equals(closed.getSource())) {
                        write(closed, h, previous, today.minusDays(1), HoldingMonthSnapshot.CLOSING,
                                AdvisorService.value(h), nz(h.getInvestedAmount()), rows);
                    }
                } else if (closed == null) {
                    BigDecimal since = HoldingLinks.net(rows, h, previous.atEndOfMonth(), null);
                    write(null, h, previous, previous.atEndOfMonth(), HoldingMonthSnapshot.REBUILT,
                            AdvisorService.value(h).subtract(since), nz(h.getInvestedAmount()).subtract(since), rows);
                }
            }
            // This month, every day.
            HoldingMonthSnapshot now = snapshotRepository.findByInvestmentIdAndMonth(h.getId(), month.atDay(1)).orElse(null);
            if (now == null || !HoldingMonthSnapshot.CLOSING.equals(now.getSource())) {
                write(now, h, month, today, HoldingMonthSnapshot.DAILY, AdvisorService.value(h),
                        nz(h.getInvestedAmount()), rows);
            }
        }
    }

    /**
     * The position record: on the 1st, first the month just ended, as of its last day; a month just
     * ended with no record at all (the 1st was missed) gets one rebuilt as of its last day — wallets
     * from the rows, the holdings it had at their rebuilt values (one bought since is left out, as in
     * {@link #recordHoldings}), what is owed to the owner as it is today (no history of it is kept)
     * — never overwriting one; then this month's.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPosition(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        YearMonth previous = month.minusMonths(1);
        List<Investment> holdings = uzsHoldings();
        PositionReader reader = new PositionReader(monthCloseService, emergencyRepository, overviewService,
                loanGivenRepository);
        if (today.getDayOfMonth() == 1) {
            remember(previous, reader.position(today.minusDays(1), holdings, AdvisorService::value));
        } else if (positionSnapshotRepository.findByMonth(previous.atDay(1)).isEmpty()) {
            List<Investment> then = holdings.stream().filter(h -> existedBy(h, previous)).toList();
            Map<Long, BigDecimal> rebuilt = new HashMap<>();
            for (Investment h : then) {
                rebuilt.put(h.getId(), HoldingLinks.rebuiltValue(linkedRows(h), h, previous));
            }
            Function<Investment, BigDecimal> valueOf = h -> rebuilt.getOrDefault(h.getId(), AdvisorService.value(h));
            remember(previous, reader.position(previous.atEndOfMonth(), then, valueOf));
        }
        remember(month, reader.position(today, holdings, AdvisorService::value));
    }

    private void remember(YearMonth month, Position p) {
        positionSnapshotService.record(month, p.getAsOf(), p.getWallets(), p.getEmergencyFund(), p.getInvestments(),
                p.getGoals(), p.getLoansLeft(), p.getOwedToYou());
    }

    /** Insert, or rewrite {@code row}, with the holding's figures for {@code month} as of {@code asOf}. */
    private void write(HoldingMonthSnapshot row, Investment h, YearMonth month, LocalDate asOf, String source,
                       BigDecimal value, BigDecimal invested, List<Transaction> rows) {
        HoldingMonthSnapshot s = row != null ? row : new HoldingMonthSnapshot();
        s.setInvestmentId(h.getId());
        s.setMonth(month.atDay(1));
        s.setAsOf(asOf);
        s.setValue(value);
        s.setInvested(invested);
        LocalDate before = month.atDay(1).minusDays(1);
        s.setPutInMonth(HoldingLinks.putIn(rows, h, before, asOf));
        s.setTakenOutMonth(HoldingLinks.takenOut(rows, h, before, asOf));
        s.setSource(source);
        s.setKind(HoldingLinks.kindOf(h));
        boolean goal = Boolean.TRUE.equals(h.getSavingsGoal());
        s.setWish(goal && Boolean.TRUE.equals(h.getWish()));
        s.setTarget(goal ? h.getTargetAmount() : null);
        s.setMonthly(goal ? h.getMonthlyContribution() : null);
        s.setDeadline(goal ? h.getTargetDate() : null);
        snapshotRepository.save(s);
    }

    private List<Investment> uzsHoldings() {
        List<Investment> out = new ArrayList<>();
        List<Investment> every = investmentRepository.findAll();
        for (Investment i : every == null ? List.<Investment>of() : every) {
            if (i.getId() != null && (i.getCurrency() == null || i.getCurrency() == Currency.UZS)) out.add(i);
        }
        return out;
    }

    /** The holding's rows by either link: those naming it, and the one that created it. */
    private List<Transaction> linkedRows(Investment h) {
        List<Transaction> rows = new ArrayList<>();
        List<Transaction> named = transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(h.getId());
        if (named != null) rows.addAll(named);
        Long first = h.getOriginatingTransactionId();
        if (first != null && rows.stream().noneMatch(t -> first.equals(t.getId()))) {
            transactionRepository.findById(first).ifPresent(rows::add);
        }
        return rows;
    }

    /** A holding bought after {@code month} has nothing to note for it. */
    private static boolean existedBy(Investment h, YearMonth month) {
        return h.getPurchaseDate() == null || !h.getPurchaseDate().isAfter(month.atEndOfMonth());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
