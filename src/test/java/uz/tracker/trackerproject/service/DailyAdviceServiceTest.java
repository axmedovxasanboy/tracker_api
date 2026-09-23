package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Daily;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.IncomePart;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Upcoming;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LevelAllocationRuleRepository;
import uz.tracker.trackerproject.repository.LevelConfigRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.MarkPaidRepository;
import uz.tracker.trackerproject.repository.MonthlyPaymentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-day walk on its own: which money it expects and when, what it counts as already paid,
 * where the horizon ends, and what counts as everyday spending. The Plan's own "paid this month"
 * logic runs for real (an {@link OverviewService} over the same mocked repositories), so these
 * pin that the daily figure agrees with the bills the Plan and the advisor list.
 */
class DailyAdviceServiceTest {

    private static final LocalDate SEP_23 = LocalDate.of(2026, 9, 23);
    private static final Category SALARY = TransactionLedger.category("Salary", false, null);

    private TransactionLedger ledger;
    private List<MonthlyPayment> bills;
    private List<BankLoan> banks;
    private List<LoanTaken> loans;
    private List<Debt> debts;
    private List<MarkPaid> marks;
    private Settings settings;
    private DailyAdviceService service;

    @BeforeEach
    void setUp() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        MarkPaidRepository markPaidRepository = mock(MarkPaidRepository.class);
        SettingsService settingsService = mock(SettingsService.class);

        ledger = new TransactionLedger(transactionRepository);
        bills = new ArrayList<>();
        banks = new ArrayList<>();
        loans = new ArrayList<>();
        debts = new ArrayList<>();
        marks = new ArrayList<>();
        when(monthlyPaymentRepository.findAll()).thenReturn(bills);
        when(bankLoanRepository.findAll()).thenReturn(banks);
        when(loanTakenRepository.findAll()).thenReturn(loans);
        when(debtRepository.findAll()).thenReturn(debts);
        when(markPaidRepository.findByMonth(any())).thenAnswer(inv -> marks.stream()
                .filter(m -> m.getMonth().equals(inv.getArgument(0))).toList());
        when(markPaidRepository.findByKindAndRefIdAndMonth(any(), any(), any())).thenAnswer(inv -> marks.stream()
                .filter(m -> m.getKind().equals(inv.getArgument(0)) && m.getRefId().equals(inv.getArgument(1))
                        && m.getMonth().equals(inv.getArgument(2))).toList());

        settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("7000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(LocalDate.of(2026, 9, 1));
        when(settingsService.getOrCreate()).thenReturn(settings);

        OverviewService overview = new OverviewService(transactionRepository, monthlyPaymentRepository,
                bankLoanRepository, loanTakenRepository, debtRepository, mock(DonationRepository.class),
                mock(InvestmentRepository.class), mock(LevelAllocationRuleRepository.class),
                mock(LevelConfigRepository.class), markPaidRepository, settingsService);
        service = new DailyAdviceService(overview, settingsService, transactionRepository,
                monthlyPaymentRepository, bankLoanRepository, loanTakenRepository, debtRepository);
    }

    private Daily daily(LocalDate today, String have, String salaryComing) {
        return compute(today, have, salaryComing, "0", "0").daily();
    }

    private DailyAdviceService.Result compute(LocalDate today, String have, String salaryComing,
                                              String setAsideLeft, String pctSum) {
        return service.compute(new DailyAdviceService.Inputs(today, new BigDecimal(have),
                new BigDecimal("7000000"), new BigDecimal(salaryComing), new BigDecimal(setAsideLeft),
                new BigDecimal(pctSum)));
    }

    /** The whole salary on the 7th: the main payday is the 7th. */
    private void salaryOnThe7th() {
        ledger.income(LocalDate.of(2026, 9, 7), "7000000", SALARY);
    }

    private MonthlyPayment bill(long id, String name, String amount, int dueDay) {
        MonthlyPayment m = new MonthlyPayment();
        m.setId(id);
        m.setName(name);
        m.setAmount(new BigDecimal(amount));
        m.setCurrency(Currency.UZS);
        m.setDueDay(dueDay);
        m.setActive(true);
        bills.add(m);
        return m;
    }

    private BankLoan bank(long id, String monthly, LocalDate taken, LocalDate end) {
        BankLoan b = new BankLoan();
        b.setId(id);
        b.setBankName("Kapitalbank");
        b.setLoanName("Loan " + id);
        b.setMonthlyPayment(new BigDecimal(monthly));
        b.setTotalAmount(new BigDecimal("20000000"));
        b.setCurrency(Currency.UZS);
        b.setTakenDate(taken);
        b.setEndDate(end);
        banks.add(b);
        return b;
    }

    private static List<Upcoming> kind(Daily d, String kind) {
        return d.getUpcoming().stream().filter(u -> kind.equals(u.getKind())).toList();
    }

    // ── Must-pays ─────────────────────────────────────────────────────────────

    @Test
    void anUnpaidBillWhoseDayHasPassedIsDueTodayAndEachLaterMonthOnItsDay() {
        salaryOnThe7th();
        bill(1, "Rent", "4200000", 10);

        Daily d = daily(SEP_23, "9000000", "0");

        assertThat(d.getUpcoming())
                .extracting(Upcoming::getDate, Upcoming::getKind, Upcoming::getRefId, Upcoming::getName,
                        u -> u.getAmount().toPlainString(), Upcoming::isOverdue)
                .containsExactly(
                        tuple(SEP_23, "BILL", 1L, "Rent", "4200000", true),
                        tuple(LocalDate.of(2026, 10, 10), "BILL", 1L, "Rent", "4200000", false));
    }

    @Test
    void aBillPaidThisMonthIsSkippedAndAPartPaymentLeavesTheRest() {
        salaryOnThe7th();
        bill(1, "Rent", "4200000", 10);
        bill(2, "Noon", "1100000", 9);
        ledger.billPaid(LocalDate.of(2026, 9, 8), "1000000", 1);   // a part of the rent
        MarkPaid noon = new MarkPaid();                               // Noon paid from money the app doesn't track
        noon.setKind("SUBSCRIPTION");
        noon.setRefId(2L);
        noon.setMonth(LocalDate.of(2026, 9, 1));
        noon.setAmount(new BigDecimal("1100000"));
        noon.setCurrency(Currency.UZS);
        marks.add(noon);

        List<Upcoming> septemberDue = daily(SEP_23, "9000000", "0").getUpcoming().stream()
                .filter(u -> u.getDate().equals(SEP_23)).toList();

        assertThat(septemberDue).singleElement().satisfies(u -> {
            assertThat(u.getName()).isEqualTo("Rent");
            assertThat(u.getAmount()).isEqualByComparingTo("3200000");
            assertThat(u.isOverdue()).isTrue();
        });
    }

    /**
     * Two loans, 500,000 paid this month: the Plan counts it against the installments as a whole,
     * so the earlier one is covered and the later one still owes 200,000. A loan ending on
     * 20 October still owes October, and nothing after.
     */
    @Test
    void bankInstallmentsFollowWhatThePlanCountsAsPaidAndStopAfterTheEndDate() {
        salaryOnThe7th();
        bank(1, "400000", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 10, 20));
        bank(2, "300000", LocalDate.of(2026, 2, 25), null);
        ledger.add(LocalDate.of(2026, 9, 6), TransactionType.EXPENSE, TransactionSubType.BANK_LOAN_PAYMENT, "500000");

        Daily d = daily(SEP_23, "9000000", "0");

        assertThat(kind(d, "BANK"))
                .extracting(Upcoming::getDate, Upcoming::getRefId, u -> u.getAmount().toPlainString(), Upcoming::getName)
                .containsExactly(
                        tuple(LocalDate.of(2026, 9, 25), 2L, "200000", "Kapitalbank · Loan 2"),
                        tuple(LocalDate.of(2026, 10, 5), 1L, "400000", "Kapitalbank · Loan 1"),
                        tuple(LocalDate.of(2026, 10, 25), 2L, "300000", "Kapitalbank · Loan 2"));
        // November: loan 1 has ended and loan 2's 25 November is past the horizon (6 Nov), so the
        // walk to 6 November pays 200,000 + 400,000 + 300,000 and nothing more.
        assertThat(d.getUntil()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.getTightestOn()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.getBreakdown().getGoingOut()).isEqualByComparingTo("900000");
    }

    /** 34% of a 1,000,000 debt is 340,000 a month — but only 500,000 is left, so 340,000 then 160,000. */
    @Test
    void aDebtIsAskedMonthByMonthUntilWhatIsLeftRunsOut() {
        salaryOnThe7th();
        Debt d = new Debt();
        d.setId(9L);
        d.setCreditorName("Aziz");
        d.setTotalAmount(new BigDecimal("1000000"));
        d.setPaidAmount(new BigDecimal("500000"));
        d.setCurrency(Currency.UZS);
        d.setStatus(RecordStatus.PARTIALLY_PAID);
        d.setPaymentStartDate(LocalDate.of(2026, 8, 1));
        debts.add(d);
        Debt settled = new Debt();
        settled.setId(10L);
        settled.setCreditorName("Old");
        settled.setTotalAmount(new BigDecimal("1000000"));
        settled.setPaidAmount(new BigDecimal("400000"));
        settled.setCurrency(Currency.UZS);
        settled.setStatus(RecordStatus.PAID);                          // marked paid: never asked for
        debts.add(settled);

        assertThat(kind(daily(SEP_23, "9000000", "0"), "DEBT"))
                .extracting(Upcoming::getDate, Upcoming::getRefId, u -> u.getAmount().stripTrailingZeros().toPlainString(),
                        Upcoming::isOverdue)
                .containsExactly(
                        tuple(SEP_23, 9L, "340000", true),
                        tuple(LocalDate.of(2026, 10, 1), 9L, "160000", false));
    }

    /**
     * A loan on a 500,000/month plan whose September payment was made: September is done, October
     * asks 500,000 — the PLAN, never the legacy monthlyPayment column (which holds the whole sum).
     */
    @Test
    void aLoanOnAPlanAsksThePlannedAmountAndThisMonthsRepaymentCoversThisMonth() {
        salaryOnThe7th();
        LoanTaken parents = new LoanTaken();
        parents.setId(3L);
        parents.setLenderName("Parents");
        parents.setTotalAmount(new BigDecimal("50000000"));
        parents.setPaidAmount(new BigDecimal("500000"));
        parents.setMonthlyPayment(new BigDecimal("50000000"));
        parents.setPlannedMonthlyPayment(new BigDecimal("500000"));
        parents.setCurrency(Currency.UZS);
        parents.setStatus(RecordStatus.PARTIALLY_PAID);
        parents.setPaymentStartDate(LocalDate.of(2026, 9, 1));
        loans.add(parents);
        ledger.add(LocalDate.of(2026, 9, 2), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "500000")
                .setRepaidLoanTakenId(3L);

        assertThat(kind(daily(SEP_23, "9000000", "0"), "LOAN"))
                .extracting(Upcoming::getDate, u -> u.getAmount().toPlainString(), Upcoming::getName)
                .containsExactly(tuple(LocalDate.of(2026, 10, 1), "500000", "Parents"));
    }

    // ── Income and the horizon ────────────────────────────────────────────────

    /**
     * 10 October, September's shape (5M on the 7th, 2M on the 15th). Only 2M came on the 7th, so
     * 3M of that part is missing — but its day has passed, and nobody knows when it will come: it is
     * left out rather than guessed onto today or the month's last day. The 15th's 2M is still ahead
     * and expected on its day.
     */
    @Test
    void aPartStillAheadIsExpectedOnItsDayAndAPartOverdueIsLeftOut() {
        ledger.income(LocalDate.of(2026, 9, 7), "5000000", SALARY);
        ledger.income(LocalDate.of(2026, 9, 15), "2000000", SALARY);
        ledger.income(LocalDate.of(2026, 10, 7), "2000000", SALARY);

        Daily d = daily(LocalDate.of(2026, 10, 10), "3000000", "5000000");

        assertThat(d.getUntil()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(d.getIncomes())
                .extracting(IncomePart::getDate, i -> i.getAmount().toPlainString(), IncomePart::getName)
                .containsExactly(
                        tuple(LocalDate.of(2026, 10, 15), "2000000", "Salary"),
                        tuple(LocalDate.of(2026, 11, 7), "5000000", "Salary"),
                        tuple(LocalDate.of(2026, 11, 15), "2000000", "Salary"));
    }

    /**
     * Settings says 8M but the salary is 7M (5M + 2M). With both parts in, the 1M "still coming" by
     * Settings matches no part — it is money that will not come, so nothing more is expected.
     */
    @Test
    void whatSettingsExpectsBeyondTheUsualPartsIsNotExpected() {
        settings.setMonthlyStableIncome(new BigDecimal("8000000"));
        ledger.income(LocalDate.of(2026, 8, 7), "5000000", SALARY);
        ledger.income(LocalDate.of(2026, 8, 15), "2000000", SALARY);
        ledger.income(LocalDate.of(2026, 9, 7), "5000000", SALARY);
        ledger.income(LocalDate.of(2026, 9, 15), "2000000", SALARY);

        Daily d = service.compute(new DailyAdviceService.Inputs(SEP_23, new BigDecimal("3000000"),
                new BigDecimal("8000000"), new BigDecimal("1000000"), BigDecimal.ZERO, BigDecimal.ZERO)).daily();

        assertThat(d.getIncomes()).extracting(IncomePart::getDate)
                .containsExactly(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 15));
    }

    @Test
    void withNoSalaryHistoryTheStableIncomeIsExpectedOnTheFirstAndTheHorizonIsNextMonth() {
        Daily d = daily(SEP_23, "1000000", "7000000");

        assertThat(d.getUntil()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(d.getIncomes())
                .extracting(IncomePart::getDate, i -> i.getAmount().toPlainString())
                .containsExactly(
                        tuple(LocalDate.of(2026, 9, 30), "7000000"),
                        tuple(LocalDate.of(2026, 10, 1), "7000000"));
    }

    /** The owner's September: 5,889,000 + 2,000,000 is more than the 7M Settings says. */
    @Test
    void partsAboveTheStableIncomeAreScaledDownToItInWholeThousands() {
        NavigableMap<Integer, BigDecimal> parts = new TreeMap<>();
        parts.put(7, new BigDecimal("5889000"));
        parts.put(15, new BigDecimal("2000000"));

        NavigableMap<Integer, BigDecimal> scaled = DailyAdviceService.capAt(parts, new BigDecimal("7000000"));

        assertThat(scaled.get(7)).isEqualByComparingTo("5225000");
        assertThat(scaled.get(15)).isEqualByComparingTo("1775000");
        // Within the stable income nothing changes: a smaller salary is projected as it came.
        NavigableMap<Integer, BigDecimal> small = new TreeMap<>();
        small.put(7, new BigDecimal("6000000"));
        assertThat(DailyAdviceService.capAt(small, new BigDecimal("7000000")).get(7)).isEqualByComparingTo("6000000");
    }

    @Test
    void theHorizonEndsTheDayBeforeTheSecondMainPaydayStillAhead() {
        assertThat(DailyAdviceService.horizonEnd(7, SEP_23)).isEqualTo(LocalDate.of(2026, 11, 6));
        // On payday itself the salary is today's news: the next two paydays are November and December.
        assertThat(DailyAdviceService.horizonEnd(7, LocalDate.of(2026, 10, 7))).isEqualTo(LocalDate.of(2026, 12, 6));
        // A payday on the 31st falls on each month's last day.
        assertThat(DailyAdviceService.horizonEnd(31, LocalDate.of(2026, 1, 15))).isEqualTo(LocalDate.of(2026, 2, 27));
    }

    // ── The daily figure ──────────────────────────────────────────────────────

    /**
     * 1M in the wallets and the rent (4.2M, due on the 10th) still unpaid: even spending nothing,
     * today is 3.2M short. The breakdown explains that day.
     */
    @Test
    void whenEvenSpendingNothingFallsShortItSaysWhenAndByHowMuch() {
        salaryOnThe7th();
        bill(1, "Rent", "4200000", 10);

        Daily d = daily(SEP_23, "1000000", "0");

        assertThat(d.getSafePerDay()).isEqualByComparingTo("0");
        assertThat(d.getShortBy().getDate()).isEqualTo(SEP_23);
        assertThat(d.getShortBy().getAmount()).isEqualByComparingTo("3200000");
        assertThat(d.getTightestOn()).isEqualTo(SEP_23);
        assertThat(d.getBreakdown().getNet()).isEqualByComparingTo("-3200000");
        assertThat(d.getBreakdown().getDays()).isEqualTo(1);
    }

    /**
     * Plenty of money, spent at 100,000 a day: it lasts. The surplus the advisor may suggest putting
     * away is the LEAST ever left over at that pace — on the eve of payday (20M − 14 × 100,000),
     * not at the horizon's end (27M − 45 × 100,000 = 22.5M), where the salary has refilled it.
     */
    @Test
    void theSurplusIsTheLeastLeftOverAtThePaceOnAnyDay() {
        salaryOnThe7th();
        ledger.expense(LocalDate.of(2026, 9, 10), "2300000");   // 23 days × 100,000

        DailyAdviceService.Result r = compute(SEP_23, "20000000", "0", "0", "0");

        Daily d = r.daily();
        assertThat(d.getPaceDaily()).isEqualByComparingTo("100000");
        assertThat(d.getRunsOutOn()).isNull();
        assertThat(d.getShortBy()).isNull();
        // 20M + 7M on 7 October, spread over the 45 days to 6 November = 600,000 a day (the eve of
        // payday allows more: 20M over 14 days).
        assertThat(d.getTightestOn()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.getSafePerDay()).isEqualByComparingTo("600000");
        assertThat(r.surplus()).isEqualByComparingTo("18600000");
    }

    /**
     * 3M in hand, the 2M rent on 5 October and the salary on the 7th, spending 100,000 a day: by the
     * 6th only 1M − 1.4M is left — nothing is spare, however much the 7th brings in.
     */
    @Test
    void aDipBeforePaydayIsWhatMakesMoneyNotSpare() {
        salaryOnThe7th();
        bill(1, "Rent", "2000000", 5);
        ledger.billPaid(LocalDate.of(2026, 9, 5), "2000000", 1);
        ledger.expense(LocalDate.of(2026, 9, 10), "2300000");   // 100,000 a day

        DailyAdviceService.Result r = compute(SEP_23, "3000000", "0", "0", "0");

        // 3M − 2M on the 5th − 14 days × 100,000 on the 6th
        assertThat(r.surplus()).isEqualByComparingTo("-400000");
        assertThat(r.daily().getRunsOutOn()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    // ── Pace ──────────────────────────────────────────────────────────────────

    /**
     * Only everyday spending sets the pace: 700,000 bought + 320,000 found by a check-in − 100,000
     * a check-in found extra = 920,000 over the 23 days since tracking started = 40,000 a day.
     * Bills, loans, savings, lending and transfers are must-pays or savings, not living.
     */
    @Test
    void thePaceCountsOnlyEverydaySpending() {
        salaryOnThe7th();
        ledger.expense(LocalDate.of(2026, 9, 3), "700000");
        ledger.add(LocalDate.of(2026, 9, 15), TransactionType.EXPENSE, TransactionSubType.EVERYDAY_SPENDING, "320000");
        ledger.add(LocalDate.of(2026, 9, 20), TransactionType.INCOME, TransactionSubType.EVERYDAY_SPENDING, "100000");

        ledger.billPaid(LocalDate.of(2026, 9, 8), "4200000", 1);
        Transaction out = ledger.add(LocalDate.of(2026, 9, 9), TransactionType.EXPENSE, TransactionSubType.TRANSFER_OUT, "1000000");
        out.setTransferPairId(out.getId());
        Transaction legacyTransfer = ledger.expense(LocalDate.of(2026, 9, 9), "250000"); // an old pair with no sub-type
        legacyTransfer.setSubType(null);
        legacyTransfer.setTransferPairId(77L);
        for (TransactionSubType st : List.of(TransactionSubType.BANK_LOAN_PAYMENT, TransactionSubType.LOAN_REPAYMENT,
                TransactionSubType.LOAN_GIVEN, TransactionSubType.DONATION, TransactionSubType.EMERGENCY_CONTRIBUTION,
                TransactionSubType.INVESTMENT, TransactionSubType.STOCK_PURCHASE)) {
            ledger.add(LocalDate.of(2026, 9, 12), TransactionType.EXPENSE, st, "300000");
        }
        ledger.expense(LocalDate.of(2026, 9, 12), "90000").setCurrency(Currency.USD);   // a dormant foreign pot

        Daily d = daily(SEP_23, "9000000", "0");

        assertThat(d.getPaceDaily()).isEqualByComparingTo("40000");
        assertThat(d.getPaceFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(d.getPaceTo()).isEqualTo(SEP_23);
    }

    @Test
    void noPaceUntilThereIsAWeekOfDataAndNeverMoreThanThirtyDays() {
        salaryOnThe7th();
        ledger.expense(LocalDate.of(2026, 9, 2), "600000");

        Daily early = daily(LocalDate.of(2026, 9, 6), "9000000", "0");     // 6 days since tracking started
        assertThat(early.getPaceDaily()).isNull();
        assertThat(early.getPaceFrom()).isNull();
        assertThat(early.getRunsOutOn()).isNull();

        assertThat(daily(LocalDate.of(2026, 9, 7), "9000000", "0").getPaceDaily()).isEqualByComparingTo("85714");

        settings.setAllocationTrackingStartMonth(null);                   // no start: the last 30 days
        Daily late = daily(LocalDate.of(2026, 10, 15), "9000000", "0");
        assertThat(late.getPaceFrom()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(late.getPaceDaily()).isEqualByComparingTo("0");       // the 2 September spend is outside
    }

    @Test
    void theUpcomingListLooksFiveWeeksAheadEvenPastTheHorizon() {
        salaryOnThe7th();
        bill(1, "Internet", "200000", 8);

        // 6 October: the horizon ends 6 November, but 8 November is within 34 days and is listed.
        Daily d = daily(LocalDate.of(2026, 10, 6), "9000000", "7000000");

        assertThat(d.getUntil()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.getUpcoming()).extracting(Upcoming::getDate)
                .containsExactly(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 11, 8));
        // Listed, but not part of the walk: only October's 200,000 is paid by 6 November.
        assertThat(d.getTightestOn()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.getBreakdown().getGoingOut()).isEqualByComparingTo("200000");
    }

    /**
     * The salary is in, so September's 884,000 is set aside today; October's 15% of (7M − 5.3M
     * bills) = 255,000 on 7 October, out of the salary that funds it. November's falls on
     * 7 November with its salary — both past the horizon (6 November).
     */
    @Test
    void savingsAreReservedTodayAndOnEachLaterMainPaydayAtTheLevelsPercentages() {
        salaryOnThe7th();
        bill(1, "Rent", "4200000", 10);
        bill(2, "Noon", "1100000", 9);
        ledger.billPaid(LocalDate.of(2026, 9, 8), "4200000", 1);
        ledger.billPaid(LocalDate.of(2026, 9, 9), "1100000", 2);

        Daily d = compute(SEP_23, "20000000", "0", "884000", "15").daily();

        assertThat(d.getTightestOn()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.getBreakdown().getSavings()).isEqualByComparingTo("1139000");
        assertThat(d.getBreakdown().getComingIn()).isEqualByComparingTo("7000000");
        assertThat(d.getBreakdown().getGoingOut()).isEqualByComparingTo("5300000");
        // 20M + 7M − 5.3M − 1.139M
        assertThat(d.getBreakdown().getNet()).isEqualByComparingTo("20561000");
        assertThat(d.getBreakdown().getDays()).isEqualTo(45);
        // 20,561,000 ÷ 45 = 456,911 → 456,000
        assertThat(d.getSafePerDay()).isEqualByComparingTo("456000");
    }

    /** A legacy debt with no payment-start month always counts (the Plan's rule), asked on the 1st. */
    @Test
    void aLegacyDebtWithNoPaymentStartIsAskedFromTheFirst() {
        salaryOnThe7th();
        Debt d = new Debt();
        d.setId(4L);
        d.setCreditorName("Shop");
        d.setTotalAmount(new BigDecimal("300000"));
        d.setPaidAmount(BigDecimal.ZERO);
        d.setCurrency(Currency.UZS);
        d.setStatus(RecordStatus.PENDING);
        debts.add(d);

        assertThat(kind(daily(SEP_23, "9000000", "0"), "DEBT"))
                .extracting(Upcoming::getDate, u -> u.getAmount().stripTrailingZeros().toPlainString())
                .containsExactly(
                        tuple(SEP_23, "102000"),
                        tuple(LocalDate.of(2026, 10, 1), "102000"));
    }
}
