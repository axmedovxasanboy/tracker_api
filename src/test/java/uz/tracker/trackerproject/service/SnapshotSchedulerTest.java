package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.entity.HoldingMonthSnapshot;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.PositionSnapshot;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The daily snapshot job (ANALYTICS-V2-SPEC §4.6): every holding's month noted DAILY, closed on the
 * 1st, caught up (REBUILT) only where a month has no row, never overwriting one; the position record
 * kept going; and the job itself — 00:15 in Tashkent, never twice at once.
 */
class SnapshotSchedulerTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);

    private final List<Investment> holdings = new ArrayList<>();
    private final List<Transaction> rows = new ArrayList<>();
    private final List<HoldingMonthSnapshot> table = new ArrayList<>();
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final HoldingMonthSnapshotRepository snapshots = mock(HoldingMonthSnapshotRepository.class);
    private final PositionSnapshotService positions = mock(PositionSnapshotService.class);
    private final PositionSnapshotRepository positionRows = mock(PositionSnapshotRepository.class);
    private final MonthCloseService monthClose = mock(MonthCloseService.class);
    private final OverviewService overview = mock(OverviewService.class);
    private final HoldingSnapshotService service;
    private long nextId = 1;

    SnapshotSchedulerTest() {
        InvestmentRepository investments = mock(InvestmentRepository.class);
        when(investments.findAll()).thenReturn(holdings);
        when(transactions.findByInvestmentIdOrderByTransactionDateDesc(any())).thenAnswer(inv ->
                rows.stream().filter(t -> Objects.equals(t.getInvestmentId(), inv.getArgument(0))).toList());
        when(transactions.findById(any())).thenAnswer(inv ->
                rows.stream().filter(t -> Objects.equals(t.getId(), inv.getArgument(0))).findFirst());
        when(snapshots.findByInvestmentIdAndMonth(any(), any())).thenAnswer(inv -> table.stream()
                .filter(s -> s.getInvestmentId().equals(inv.getArgument(0)) && s.getMonth().equals(inv.getArgument(1)))
                .findFirst());
        when(snapshots.save(any())).thenAnswer(inv -> {
            HoldingMonthSnapshot s = inv.getArgument(0);
            if (table.stream().noneMatch(x -> x == s)) table.add(s);
            return s;
        });
        when(positionRows.findByMonth(any())).thenReturn(Optional.empty());
        when(monthClose.computedWallets(any(), any())).thenAnswer(inv -> List.of(new MonthCloseService.ComputedWallet(
                "CARD", null, Currency.UZS, "Card", inv.getArgument(0).equals(SEP.atEndOfMonth())
                        ? new BigDecimal("900000") : new BigDecimal("1000000"))));
        when(overview.openLoans(any())).thenReturn(List.of());
        service = new HoldingSnapshotService(investments, transactions, snapshots, positions, positionRows, monthClose,
                mock(EmergencyRepository.class), overview, mock(LoanGivenRepository.class));
    }

    /** A goal bought in August, worth 3,000,000 now, with 500,000 put in on 5 October. */
    private Investment goal() {
        Investment g = new Investment();
        g.setId(11L);
        g.setName("Ota-onam");
        g.setType(InvestmentType.OTHER);
        g.setCurrency(Currency.UZS);
        g.setPurchaseDate(LocalDate.of(2026, 8, 1));
        g.setInvestedAmount(new BigDecimal("3000000"));
        g.setSavingsGoal(true);
        g.setTargetAmount(new BigDecimal("50000000"));
        g.setMonthlyContribution(new BigDecimal("1000000"));
        holdings.add(g);
        put(LocalDate.of(2026, 9, 20), "700000", 11L);
        put(LocalDate.of(2026, 10, 5), "500000", 11L);
        return g;
    }

    private Transaction put(LocalDate date, String amount, Long holdingId) {
        Transaction t = new Transaction();
        t.setId(nextId++);
        t.setType(TransactionType.EXPENSE);
        t.setSubType(TransactionSubType.INVESTMENT);
        t.setAllocationBucket(AllocationBucket.SAVINGS);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency(Currency.UZS);
        t.setTransactionDate(date);
        t.setDescription("Top-up");
        t.setInvestmentId(holdingId);
        rows.add(t);
        return t;
    }

    private HoldingMonthSnapshot row(YearMonth month) {
        return table.stream().filter(s -> s.getMonth().equals(month.atDay(1))).findFirst().orElse(null);
    }

    @Test
    void everyDayThisMonthsRowIsRewritten_runningTwiceWritesTheSameRow() {
        Investment g = goal();
        table.add(existing(11L, SEP, HoldingMonthSnapshot.CLOSING, "2500000"));

        service.recordHoldings(LocalDate.of(2026, 10, 12));
        service.recordHoldings(LocalDate.of(2026, 10, 12));

        assertThat(table).hasSize(2);
        HoldingMonthSnapshot oct = row(OCT);
        assertThat(oct.getSource()).isEqualTo(HoldingMonthSnapshot.DAILY);
        assertThat(oct.getAsOf()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(oct.getValue()).isEqualByComparingTo("3000000");
        assertThat(oct.getPutInMonth()).isEqualByComparingTo("500000");
        assertThat(oct.getKind()).isEqualTo("GOAL");
        assertThat(oct.getMonthly()).isEqualByComparingTo("1000000");
        assertThat(oct.getTarget()).isEqualByComparingTo("50000000");
        assertThat(oct.isWish()).isFalse();

        g.setCurrentValue(new BigDecimal("3100000"));
        service.recordHoldings(LocalDate.of(2026, 10, 13));
        assertThat(table).hasSize(2);
        assertThat(row(OCT).getValue()).isEqualByComparingTo("3100000");
        assertThat(row(OCT).getAsOf()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void onTheFirstTheMonthJustEndedIsClosed_andNothingOverwritesTheClosingRow() {
        Investment g = goal();
        table.add(existing(11L, SEP, HoldingMonthSnapshot.DAILY, "2400000"));

        service.recordHoldings(LocalDate.of(2026, 10, 1));

        HoldingMonthSnapshot sep = row(SEP);
        assertThat(sep.getSource()).isEqualTo(HoldingMonthSnapshot.CLOSING);
        assertThat(sep.getAsOf()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(sep.getValue()).isEqualByComparingTo("3000000");
        assertThat(sep.getPutInMonth()).isEqualByComparingTo("700000");
        assertThat(row(OCT).getSource()).isEqualTo(HoldingMonthSnapshot.DAILY);

        g.setCurrentValue(new BigDecimal("9999999"));
        service.recordHoldings(LocalDate.of(2026, 10, 1));
        service.recordHoldings(LocalDate.of(2026, 10, 2));
        assertThat(row(SEP).getValue()).isEqualByComparingTo("3000000");
        assertThat(row(SEP).getSource()).isEqualTo(HoldingMonthSnapshot.CLOSING);
        assertThat(table).hasSize(2);
    }

    @Test
    void aMissedFirstIsCaughtUpFromTheRows_onceAndIdempotently() {
        goal();

        service.recordHoldings(LocalDate.of(2026, 10, 7));
        service.recordHoldings(LocalDate.of(2026, 10, 8));

        HoldingMonthSnapshot sep = row(SEP);
        assertThat(sep.getSource()).isEqualTo(HoldingMonthSnapshot.REBUILT);
        assertThat(sep.getAsOf()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(sep.getValue()).isEqualByComparingTo("2500000");                  // 3,000,000 − October's 500,000
        assertThat(sep.getInvested()).isEqualByComparingTo("2500000");
        assertThat(sep.getPutInMonth()).isEqualByComparingTo("700000");
        assertThat(table).extracting("month", "source").containsExactlyInAnyOrder(
                tuple(SEP.atDay(1), HoldingMonthSnapshot.REBUILT), tuple(OCT.atDay(1), HoldingMonthSnapshot.DAILY));
    }

    @Test
    void aCatchUpNeverOverwritesARow_aDailyRowFromBeforeAnOutageIsKept() {
        goal();
        HoldingMonthSnapshot before = existing(11L, SEP, HoldingMonthSnapshot.DAILY, "2450000");
        table.add(before);

        service.recordHoldings(LocalDate.of(2026, 10, 7));

        assertThat(row(SEP)).isSameAs(before);
        assertThat(row(SEP).getSource()).isEqualTo(HoldingMonthSnapshot.DAILY);
        assertThat(row(SEP).getValue()).isEqualByComparingTo("2450000");
    }

    @Test
    void aHoldingBoughtThisMonthHasNoMonthBefore() {
        Investment g = goal();
        g.setPurchaseDate(LocalDate.of(2026, 10, 3));

        service.recordHoldings(LocalDate.of(2026, 10, 7));
        service.recordHoldings(LocalDate.of(2026, 11, 1));

        assertThat(table).extracting("month", "source").containsExactlyInAnyOrder(
                tuple(OCT.atDay(1), HoldingMonthSnapshot.CLOSING), tuple(LocalDate.of(2026, 11, 1), HoldingMonthSnapshot.DAILY));
    }

    @Test
    void thePositionIsRecordedEveryDay_theMonthJustEndedFirstOnTheFirst() {
        goal();

        service.recordPosition(LocalDate.of(2026, 10, 1));

        var order = inOrder(positions);
        order.verify(positions).record(eq(SEP), eq(LocalDate.of(2026, 9, 30)), any(), any(), any(),
                eq(new BigDecimal("3000000")), any(), any());
        order.verify(positions).record(eq(OCT), eq(LocalDate.of(2026, 10, 1)), any(), any(), any(),
                eq(new BigDecimal("3000000")), any(), any());
    }

    @Test
    void aMonthWithNoPositionIsRebuiltAsOfItsLastDay_anExistingOneIsNeverOverwritten() {
        goal();

        service.recordPosition(LocalDate.of(2026, 10, 7));

        // Wallets from the rows up to 30 September; the goal at its rebuilt value.
        verify(positions).record(eq(SEP), eq(LocalDate.of(2026, 9, 30)), eq(new BigDecimal("900000")), any(), any(),
                eq(new BigDecimal("2500000")), any(), any());
        verify(positions).record(eq(OCT), eq(LocalDate.of(2026, 10, 7)), eq(new BigDecimal("1000000")), any(), any(),
                eq(new BigDecimal("3000000")), any(), any());

        when(positionRows.findByMonth(SEP.atDay(1))).thenReturn(Optional.of(new PositionSnapshot()));
        org.mockito.Mockito.clearInvocations(positions);
        service.recordPosition(LocalDate.of(2026, 10, 8));
        verify(positions, never()).record(eq(SEP), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRebuiltPositionLeavesOutAHoldingBoughtSince() {
        goal();
        // An opening amount entered on 5 October: no row, so a rebuild would count it in September.
        Investment gold = new Investment();
        gold.setId(12L);
        gold.setName("Gold");
        gold.setType(InvestmentType.GOLD);
        gold.setCurrency(Currency.UZS);
        gold.setPurchaseDate(LocalDate.of(2026, 10, 5));
        gold.setInvestedAmount(new BigDecimal("2000000"));
        gold.setOpeningBalance(true);
        holdings.add(gold);

        service.recordPosition(LocalDate.of(2026, 10, 7));

        verify(positions).record(eq(SEP), eq(LocalDate.of(2026, 9, 30)), any(), any(), eq(BigDecimal.ZERO),
                eq(new BigDecimal("2500000")), any(), any());
        verify(positions).record(eq(OCT), eq(LocalDate.of(2026, 10, 7)), any(), any(), eq(new BigDecimal("2000000")),
                eq(new BigDecimal("3000000")), any(), any());
    }

    @Test
    void eachWriteIsATransactionOfItsOwn() throws Exception {
        for (String method : List.of("recordHoldings", "recordPosition")) {
            Transactional tx = HoldingSnapshotService.class.getMethod(method, LocalDate.class).getAnnotation(Transactional.class);
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    // ── The job ──

    @Test
    void theJobRunsAt15PastMidnightInTashkent() throws Exception {
        HoldingSnapshotService s = mock(HoldingSnapshotService.class);

        new SnapshotScheduler(s).snapshotDaily();

        verify(s).recordHoldings(any(LocalDate.class));
        verify(s).recordPosition(any(LocalDate.class));
        Scheduled scheduled = SnapshotScheduler.class.getMethod("snapshotDaily").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 15 0 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Tashkent");
    }

    @Test
    void itNeverRunsTwiceAtOnce() {
        HoldingSnapshotService s = mock(HoldingSnapshotService.class);
        SnapshotScheduler job = new SnapshotScheduler(s);
        AtomicBoolean second = new AtomicBoolean(true);
        doAnswer(inv -> {
            second.set(job.run(LocalDate.of(2026, 10, 2)));                         // a second run while this one goes
            return null;
        }).when(s).recordHoldings(LocalDate.of(2026, 10, 1));

        assertThat(job.run(LocalDate.of(2026, 10, 1))).isTrue();

        assertThat(second.get()).isFalse();
        verify(s, never()).recordHoldings(LocalDate.of(2026, 10, 2));
        assertThat(job.run(LocalDate.of(2026, 10, 3))).isTrue();                    // and it runs again afterwards
    }

    @Test
    void aFailureIsLoggedNotRetried_andThePositionIsStillRecorded() {
        HoldingSnapshotService s = mock(HoldingSnapshotService.class);
        doThrow(new IllegalStateException("db down")).when(s).recordHoldings(any());

        assertThat(new SnapshotScheduler(s).run(LocalDate.of(2026, 10, 2))).isTrue();

        verify(s).recordHoldings(LocalDate.of(2026, 10, 2));
        verify(s).recordPosition(LocalDate.of(2026, 10, 2));
    }

    @Test
    void theSnapshotIsNotedByTheSameLinkedNetTheReaderCorrectsBy() {
        goal();
        Transaction other = put(LocalDate.of(2026, 9, 25), "100000", 11L);
        other.setAllocationBucket(AllocationBucket.INVESTMENTS);                     // another bucket: still in the net
        ArgumentCaptor<HoldingMonthSnapshot> saved = ArgumentCaptor.forClass(HoldingMonthSnapshot.class);

        service.recordHoldings(LocalDate.of(2026, 10, 1));

        verify(snapshots, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(row(SEP).getPutInMonth()).isEqualByComparingTo("800000");
        assertThat(row(SEP).getTakenOutMonth()).isEqualByComparingTo("0");
    }

    private static HoldingMonthSnapshot existing(long investmentId, YearMonth month, String source, String value) {
        HoldingMonthSnapshot s = new HoldingMonthSnapshot();
        s.setInvestmentId(investmentId);
        s.setMonth(month.atDay(1));
        s.setAsOf(month.atDay(20));
        s.setValue(new BigDecimal(value));
        s.setInvested(new BigDecimal(value));
        s.setPutInMonth(BigDecimal.ZERO);
        s.setTakenOutMonth(BigDecimal.ZERO);
        s.setSource(source);
        s.setKind("GOAL");
        return s;
    }
}
