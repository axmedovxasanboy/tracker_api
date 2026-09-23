package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.tracker.trackerproject.dto.response.AdvisorResponse;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Daily;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.SavingsRow;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.ShortBy;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Suggestion;
import uz.tracker.trackerproject.dto.response.MonthClosePreviewResponse.WalletLine;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse.PendingSubscription;
import uz.tracker.trackerproject.dto.response.TierAllocation;
import uz.tracker.trackerproject.dto.response.TierAllocation.ActionItem;
import uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The advisor adds up figures the other services already compute, so these tests hand it those
 * figures directly and pin what it does with them: the four totals, the order of the advice, and
 * when it stays quiet. The owner's own numbers are used where they fit — 8M salary, rent 4.2M,
 * 1Fit 558K, 10/5/15 of a 3.242M left balance. The per-day walk is handed in the same way (its
 * own arithmetic is DailyAdviceServiceTest's; the two together, AdvisorOwnerSeptemberTest's).
 */
class AdvisorServiceTest {

    private static final LocalDate SEP_18 = LocalDate.of(2026, 9, 18);
    private static final YearMonth SEP = YearMonth.of(2026, 9);

    private OverviewService overviewService;
    private WalletCheckInService walletCheckInService;
    private MonthCloseService monthCloseService;
    private TransactionRepository transactionRepository;
    private LoanGivenRepository loanGivenRepository;
    private InvestmentRepository investmentRepository;
    private EmergencyRepository emergencyRepository;
    private DailyAdviceService dailyAdviceService;
    private AdvisorService service;

    // What each test changes before calling advise().
    private List<WalletLine> wallets;
    private Integer daysSinceChecked;
    private boolean checkDue;
    private List<PendingSubscription> pending;
    private List<ActionItem> actions;
    private List<AllocationLine> lines;
    private boolean locked;
    private boolean missingIncome;
    private BigDecimal bonus;

    @BeforeEach
    void setUp() {
        overviewService = mock(OverviewService.class);
        walletCheckInService = mock(WalletCheckInService.class);
        monthCloseService = mock(MonthCloseService.class);
        transactionRepository = mock(TransactionRepository.class);
        loanGivenRepository = mock(LoanGivenRepository.class);
        investmentRepository = mock(InvestmentRepository.class);
        emergencyRepository = mock(EmergencyRepository.class);
        dailyAdviceService = mock(DailyAdviceService.class);
        service = new AdvisorService(overviewService, walletCheckInService, monthCloseService,
                transactionRepository, loanGivenRepository, investmentRepository, emergencyRepository,
                dailyAdviceService);

        wallets = new ArrayList<>(List.of(card("MinCon", "1500000"), cash("510000")));
        daysSinceChecked = 2;
        checkDue = false;
        pending = new ArrayList<>();
        actions = new ArrayList<>();
        // Level 1.1 on a 3,242,000 left balance, nothing paid yet.
        lines = new ArrayList<>(List.of(
                line("DONATION", "10", "324200", "0"),
                line("EMERGENCY", "5", "162100", "0"),
                line("INVESTMENTS", "15", "486300", "0")));
        locked = false;
        missingIncome = false;
        bonus = BigDecimal.ZERO;

        when(loanGivenRepository.findAll()).thenReturn(List.of());
        when(investmentRepository.findAll()).thenReturn(List.of());
        when(emergencyRepository.count()).thenReturn(0L);
        when(monthCloseService.latestClosedMonth()).thenReturn(YearMonth.of(2026, 8));
        salaryRecorded("0");
    }

    private AdvisorResponse advise(LocalDate date) {
        when(walletCheckInService.status(any())).thenReturn(WalletCheckInStatusResponse.builder()
                .date(date)
                .due(checkDue)
                .daysSinceLastReconciled(daysSinceChecked)
                .lastReconciledOn(daysSinceChecked == null ? null : date.minusDays(daysSinceChecked))
                .wallets(wallets)
                .build());
        TierAllocation allocation = TierAllocation.builder()
                .lines(lines).actions(actions).allocationLocked(locked).build();
        when(overviewService.getTierIgnoringSubscriptions(any(), any())).thenReturn(OverviewTierResponse.builder()
                .missingStableIncome(missingIncome)
                .income(missingIncome ? BigDecimal.ZERO : new BigDecimal("8000000"))
                .allocationBase(new BigDecimal("3242000").add(bonus))
                .bonusIncome(bonus)
                .subscriptionsPending(!pending.isEmpty())
                .pendingSubscriptions(pending)
                .allocation(allocation)
                .build());
        return service.advise(date);
    }

    private void salaryRecorded(String amount) {
        when(transactionRepository.sumBySubTypeCurrencyDateRange(
                TransactionSubType.REGULAR_INCOME, Currency.UZS, SEP.atDay(1), SEP.atEndOfMonth()))
                .thenReturn(new BigDecimal(amount));
    }

    /** The whole month paid for: nothing pending, every bucket funded. */
    private void everythingPaid() {
        lines = new ArrayList<>(List.of(
                line("DONATION", "10", "324200", "324200"),
                line("EMERGENCY", "5", "162100", "162100"),
                line("INVESTMENTS", "15", "486300", "486300")));
    }

    private static WalletLine card(String name, String balance) {
        return WalletLine.builder().walletType("CARD").cardId(7L).label(name)
                .currency(Currency.UZS).computedBalance(new BigDecimal(balance)).build();
    }

    private static WalletLine cash(String balance) {
        return WalletLine.builder().walletType("CASH").label("Cash")
                .currency(Currency.UZS).computedBalance(new BigDecimal(balance)).build();
    }

    private static AllocationLine line(String bucket, String pct, String target, String paid) {
        BigDecimal t = new BigDecimal(target);
        BigDecimal p = new BigDecimal(paid);
        BigDecimal remaining = t.subtract(p).max(BigDecimal.ZERO);
        return AllocationLine.builder().bucket(bucket).label(bucket).recommended(true)
                .minPercent(new BigDecimal(pct)).minAmount(t).paidAmount(p).remainingAmount(remaining)
                .build();
    }

    private static PendingSubscription sub(long id, String name, String amount, String paid) {
        return PendingSubscription.builder().id(id).name(name).currency(Currency.UZS)
                .amount(new BigDecimal(amount)).paid(new BigDecimal(paid)).build();
    }

    private static Investment goal(long id, String name, String target, String invested) {
        Investment i = new Investment();
        i.setId(id);
        i.setName(name);
        i.setSavingsGoal(true);
        i.setTargetAmount(target == null ? null : new BigDecimal(target));
        i.setInvestedAmount(new BigDecimal(invested));
        return i;
    }

    /** A per-day answer that lasts: 300,000 a day is safe to 6 November, the owner spends 100,000. */
    private static Daily.DailyBuilder daily() {
        return Daily.builder()
                .safePerDay(new BigDecimal("300000"))
                .until(LocalDate.of(2026, 11, 6))
                .tightestOn(LocalDate.of(2026, 11, 6))
                .paceDaily(new BigDecimal("100000"))
                .paceFrom(LocalDate.of(2026, 9, 1))
                .paceTo(SEP_18)
                .upcoming(List.of())
                .incomes(List.of());
    }

    /** The walk leaves {@code surplus} at its horizon at the owner's own pace. */
    private void spare(String surplus) {
        when(dailyAdviceService.compute(any())).thenReturn(
                new DailyAdviceService.Result(daily().build(), new BigDecimal(surplus)));
    }

    private static List<String> codes(AdvisorResponse r) {
        return r.getSuggestions().stream().map(Suggestion::getCode).toList();
    }

    private static Suggestion find(AdvisorResponse r, String code) {
        return r.getSuggestions().stream().filter(s -> code.equals(s.getCode())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " in " + codes(r)));
    }

    // ── The four figures ──────────────────────────────────────────────────────

    @Test
    void freeIsWhatIsLeftOnceTheSalaryArrivesAndTheMonthIsPaidFor() {
        wallets.add(WalletLine.builder().walletType("CASH").label("Cash").currency(Currency.USD)
                .computedBalance(new BigDecimal("300")).build()); // a dormant USD pot
        pending.add(sub(1, "Kvartira Arenda", "4200000", "0"));
        pending.add(sub(2, "1Fit", "558000", "0"));

        AdvisorResponse r = advise(SEP_18);

        assertThat(r.getHave()).isEqualByComparingTo("2010000");           // USD pot never added
        assertThat(r.getWallets()).hasSize(2);
        assertThat(r.getSalaryComing()).isEqualByComparingTo("8000000");
        assertThat(r.getBillsLeft()).isEqualByComparingTo("4758000");
        assertThat(r.getSetAsideLeft()).isEqualByComparingTo("972600");
        // 2,010,000 + 8,000,000 − 4,758,000 − 972,600
        assertThat(r.getFree()).isEqualByComparingTo("4279400");
    }

    @Test
    void theSalaryStopsBeingComingOnceRecordedAndABonusIsNotTheSalary() {
        salaryRecorded("5000000");
        assertThat(advise(SEP_18).getSalaryComing()).isEqualByComparingTo("3000000");

        // 15M of regular income, 7M of it in a bonus category: the salary is fully in.
        salaryRecorded("15000000");
        bonus = new BigDecimal("7000000");
        AdvisorResponse r = advise(SEP_18);
        assertThat(r.getSalaryReceived()).isEqualByComparingTo("8000000");
        assertThat(r.getSalaryComing()).isEqualByComparingTo("0");
        assertThat(r.getBonusReceived()).isEqualByComparingTo("7000000");
    }

    @Test
    void moneyLentOutIsListedButNeverCountedAsFree() {
        LoanGiven mirjalol = new LoanGiven();
        mirjalol.setId(1L);
        mirjalol.setDebtorName("Mirjalol");
        mirjalol.setTotalAmount(new BigDecimal("300000"));
        mirjalol.setReceivedAmount(BigDecimal.ZERO);
        mirjalol.setCurrency(Currency.UZS);
        mirjalol.setStatus(RecordStatus.PENDING);
        LoanGiven returned = new LoanGiven();
        returned.setTotalAmount(new BigDecimal("100000"));
        returned.setReceivedAmount(new BigDecimal("100000"));
        returned.setStatus(RecordStatus.PAID);
        when(loanGivenRepository.findAll()).thenReturn(List.of(mirjalol, returned));

        AdvisorResponse r = advise(SEP_18);

        assertThat(r.getOwedToYou()).extracting(AdvisorResponse.Owed::getName).containsExactly("Mirjalol");
        assertThat(r.getOwedToYouTotal()).isEqualByComparingTo("300000");
        // 2,010,000 + 8,000,000 − 972,600: the 300,000 is nowhere in it.
        assertThat(r.getFree()).isEqualByComparingTo("9037400");
    }

    // ── Bills come first ──────────────────────────────────────────────────────

    @Test
    void unpaidBillsLeadAndHoldBackTheSetAsideButtons() {
        pending.add(sub(1, "Kvartira Arenda", "4200000", "1000000"));

        AdvisorResponse r = advise(SEP_18);

        assertThat(r.isSetAsideAfterBills()).isTrue();
        assertThat(r.getBills()).singleElement().satisfies(b -> {
            assertThat(b.getKind()).isEqualTo("SUBSCRIPTION");
            assertThat(b.getAmount()).isEqualByComparingTo("3200000"); // what is still to pay
        });
        Suggestion first = r.getSuggestions().getFirst();
        assertThat(first.getAction()).isEqualTo("PAY_SUBSCRIPTION");
        assertThat(first.getRefId()).isEqualTo(1L);
        assertThat(first.getParams()).containsEntry("name", "Kvartira Arenda");
        // The set-asides are still real figures — just not buttons yet.
        assertThat(r.getSetAside()).hasSize(3);
        assertThat(r.getSuggestions()).noneMatch(s -> "SET_ASIDE".equals(s.getAction()) && "DO".equals(s.getKind()));
    }

    @Test
    void theDebtAsksBecomeBillsByWhatIsLeft() {
        actions.add(ActionItem.builder().action("PAY_BANK").code("page.plan.actionPayBank")
                .target(new BigDecimal("400000")).paid(new BigDecimal("100000")).build());
        actions.add(ActionItem.builder().action("PAY_PERSONAL_LOAN").code("page.plan.action.setAside")
                .target(new BigDecimal("500000")).paid(BigDecimal.ZERO).build());
        actions.add(ActionItem.builder().action("PAY_PERSONAL_LOAN").code("page.plan.action.payDebts34")
                .target(new BigDecimal("340000")).paid(new BigDecimal("340000")).build()); // done
        actions.add(ActionItem.builder().code("page.plan.note.tight").text("tight").build()); // info
        locked = true;

        AdvisorResponse r = advise(SEP_18);

        assertThat(r.getBills()).extracting(AdvisorResponse.Bill::getKind).containsExactly("BANK", "LOAN_PLAN");
        assertThat(r.getBillsLeft()).isEqualByComparingTo("800000");
        assertThat(codes(r)).startsWith("advisor.s.payBank", "advisor.s.payLoanPlan");
        assertThat(r.isSetAsideAfterBills()).isTrue();
    }

    @Test
    void onceTheBillsArePaidEachBucketIsASetAsideAndTheFirstEmergencyOneStartsTheFund() {
        AdvisorResponse r = advise(SEP_18);

        assertThat(r.isSetAsideAfterBills()).isFalse();
        Suggestion emergency = find(r, "advisor.s.startEmergency");
        assertThat(emergency.getBucket()).isEqualTo("EMERGENCY");
        assertThat(emergency.getAmount()).isEqualByComparingTo("162100");
        assertThat(r.getSuggestions()).filteredOn(s -> "advisor.s.setAside".equals(s.getCode()))
                .extracting(Suggestion::getBucket).containsExactly("DONATION", "INVESTMENTS");

        when(emergencyRepository.count()).thenReturn(1L);
        assertThat(codes(advise(SEP_18))).doesNotContain("advisor.s.startEmergency");
    }

    /**
     * Rent unpaid, the salary in and already spent. The month-only {@code free} still says so; the
     * per-day shortfall travels in {@code daily} (the web's Home shows it), never as a suggestion.
     */
    @Test
    void aMonthThatAsksForMoreThanThereIsSaysSoInDaily() {
        wallets.clear();
        pending.add(sub(1, "Kvartira Arenda", "4200000", "0"));
        salaryRecorded("8000000"); // in, and already spent: nothing left in the wallets
        when(dailyAdviceService.compute(any())).thenReturn(new DailyAdviceService.Result(
                daily().safePerDay(BigDecimal.ZERO).runsOutOn(SEP_18)
                        .shortBy(ShortBy.builder().date(SEP_18).amount(new BigDecimal("5172600")).build())
                        .build(),
                new BigDecimal("-9000000")));

        AdvisorResponse r = advise(SEP_18);

        assertThat(r.getFree()).isEqualByComparingTo("-5172600");
        assertThat(r.getDaily().getShortBy().getAmount()).isEqualByComparingTo("5172600");
        assertThat(codes(r)).doesNotContain("advisor.s.short", "advisor.s.paceWarning");
        assertThat(codes(r).getFirst()).isEqualTo("advisor.s.paySubscription");
    }

    @Test
    void runningOutAtTheOwnersOwnPaceTravelsInDailyNotAsASuggestion() {
        pending.add(sub(1, "Kvartira Arenda", "4200000", "0"));
        Daily daily = daily().safePerDay(new BigDecimal("188000")).paceDaily(new BigDecimal("526000"))
                .runsOutOn(LocalDate.of(2026, 10, 10)).build();
        when(dailyAdviceService.compute(any())).thenReturn(new DailyAdviceService.Result(daily, new BigDecimal("-15207000")));

        AdvisorResponse r = advise(SEP_18);

        assertThat(r.getDaily()).isSameAs(daily);
        assertThat(codes(r)).doesNotContain("advisor.s.paceWarning", "advisor.s.short");
        assertThat(codes(r).getFirst()).isEqualTo("advisor.s.paySubscription");
    }

    @Test
    void theWalkIsHandedTheMonthsOwnFigures() {
        salaryRecorded("5000000");

        advise(SEP_18);

        ArgumentCaptor<DailyAdviceService.Inputs> in = ArgumentCaptor.forClass(DailyAdviceService.Inputs.class);
        verify(dailyAdviceService).compute(in.capture());
        assertThat(in.getValue().today()).isEqualTo(SEP_18);
        assertThat(in.getValue().have()).isEqualByComparingTo("2010000");
        assertThat(in.getValue().stableIncome()).isEqualByComparingTo("8000000");
        assertThat(in.getValue().salaryComing()).isEqualByComparingTo("3000000");
        assertThat(in.getValue().setAsideLeft()).isEqualByComparingTo("972600");
        assertThat(in.getValue().pctSum()).isEqualByComparingTo("30");
    }

    /** Every bucket with a target, met or not; one that is not asked for at this tier is left out. */
    @Test
    void savingsThisMonthListsEveryBucketWithATargetIncludingTheOnesMet() {
        lines = new ArrayList<>(List.of(
                line("DONATION", "5", "884000", "0"),
                AllocationLine.builder().bucket("EMERGENCY").label("EMERGENCY").recommended(false)
                        .paidAmount(BigDecimal.ZERO).build(),
                line("INVESTMENTS", "5", "884000", "884000")));

        AdvisorResponse r = advise(SEP_18);

        assertThat(r.getSetAside()).extracting(AdvisorResponse.SetAside::getBucket).containsExactly("DONATION");
        assertThat(r.getSavingsThisMonth()).extracting(SavingsRow::getBucket).containsExactly("DONATION", "INVESTMENTS");
        SavingsRow met = r.getSavingsThisMonth().get(1);
        assertThat(met.getPercent()).isEqualByComparingTo("5");
        assertThat(met.getTarget()).isEqualByComparingTo("884000");
        assertThat(met.getPaid()).isEqualByComparingTo("884000");
        assertThat(met.getRemaining()).isEqualByComparingTo("0");
    }

    private static Transaction contribution(long goalId, String day, String amount) {
        Transaction t = new Transaction();
        t.setType(TransactionType.EXPENSE);
        t.setSubType(TransactionSubType.INVESTMENT);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency(Currency.UZS);
        t.setTransactionDate(LocalDate.parse(day));
        t.setInvestmentId(goalId);
        return t;
    }

    /**
     * A goal with a monthly payment is one more thing to set aside this month, after the buckets:
     * what was put in by today against its payment. A goal already reached, or one without a
     * payment, asks nothing; in its last month a goal asks only what finishes it.
     */
    @Test
    void eachGoalWithAMonthlyPaymentIsASavingsRowAfterTheBuckets() {
        Investment car = goal(5, "Car", "12000000", "3000000");
        car.setMonthlyContribution(new BigDecimal("1000000"));
        Investment laptop = goal(6, "Laptop", "10000000", "9800000");
        laptop.setMonthlyContribution(new BigDecimal("1000000"));
        Investment phone = goal(7, "Phone", "5000000", "5000000");      // reached
        phone.setMonthlyContribution(new BigDecimal("500000"));
        Investment trip = goal(8, "Trip", null, "0");                    // no monthly payment
        when(investmentRepository.findAll()).thenReturn(List.of(car, laptop, phone, trip));
        when(transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(5L)).thenReturn(List.of(
                contribution(5, "2026-09-25", "300000"),   // recorded for a later day: not in yet
                contribution(5, "2026-09-10", "400000"),
                contribution(5, "2026-08-20", "200000"))); // last month

        AdvisorResponse r = advise(SEP_18);

        assertThat(r.getSavingsThisMonth())
                .extracting(SavingsRow::getBucket, SavingsRow::getRefId, SavingsRow::getName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("DONATION", null, null),
                        org.assertj.core.groups.Tuple.tuple("EMERGENCY", null, null),
                        org.assertj.core.groups.Tuple.tuple("INVESTMENTS", null, null),
                        org.assertj.core.groups.Tuple.tuple("GOAL", 5L, "Car"),
                        org.assertj.core.groups.Tuple.tuple("GOAL", 6L, "Laptop"));
        SavingsRow carRow = r.getSavingsThisMonth().get(3);
        assertThat(carRow.getPercent()).isNull();
        assertThat(carRow.getTarget()).isEqualByComparingTo("1000000");
        assertThat(carRow.getPaid()).isEqualByComparingTo("400000");
        assertThat(carRow.getRemaining()).isEqualByComparingTo("600000");
        SavingsRow laptopRow = r.getSavingsThisMonth().get(4);
        assertThat(laptopRow.getTarget()).isEqualByComparingTo("200000");   // 10M − 9.8M
        assertThat(laptopRow.getRemaining()).isEqualByComparingTo("200000");
        // The bot's lists stay as they were: goals are not in setAside.
        assertThat(r.getSetAside()).extracting(AdvisorResponse.SetAside::getBucket)
                .containsExactly("DONATION", "EMERGENCY", "INVESTMENTS");

        // The walk is handed both, to set them aside like the buckets.
        ArgumentCaptor<DailyAdviceService.Inputs> in = ArgumentCaptor.forClass(DailyAdviceService.Inputs.class);
        verify(dailyAdviceService).compute(in.capture());
        assertThat(in.getValue().goals())
                .extracting(DailyAdviceService.Goal::id, g -> g.paidThisMonth().toPlainString(),
                        g -> g.toTarget().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(5L, "400000", "9000000"),
                        org.assertj.core.groups.Tuple.tuple(6L, "0", "200000"));
    }

    // ── Wallet checks and closing a month ─────────────────────────────────────

    @Test
    void aDueWalletCheckIsSuggestedWithHowLongItHasBeen() {
        checkDue = true;
        daysSinceChecked = 11;
        assertThat(find(advise(SEP_18), "advisor.s.checkWallets").getParams()).containsEntry("days", "11");

        daysSinceChecked = null;
        assertThat(codes(advise(SEP_18))).contains("advisor.s.checkWalletsFirst");
    }

    @Test
    void lastMonthIsOfferedForClosingOnlyInTheFirstDaysAndThenInsteadOfACheck() {
        when(monthCloseService.latestClosedMonth()).thenReturn(YearMonth.of(2026, 7)); // August is next
        checkDue = true;

        AdvisorResponse early = advise(LocalDate.of(2026, 9, 3));
        assertThat(find(early, "advisor.s.closeMonth").getParams()).containsEntry("month", "2026-08");
        assertThat(codes(early)).doesNotContain("advisor.s.checkWallets", "advisor.s.checkWalletsFirst");

        AdvisorResponse late = advise(LocalDate.of(2026, 9, 8));
        assertThat(codes(late)).doesNotContain("advisor.s.closeMonth").contains("advisor.s.checkWallets");
    }

    @Test
    void closingIsNotOfferedWhenLastMonthIsDoneOrOutOfOrderOrEmpty() {
        LocalDate sep3 = LocalDate.of(2026, 9, 3);
        when(monthCloseService.latestClosedMonth()).thenReturn(YearMonth.of(2026, 8)); // Aug closed
        // latestClosedMonth = Aug → the next to close is Sep, which is not over yet.
        assertThat(codes(advise(sep3))).doesNotContain("advisor.s.closeMonth");

        when(monthCloseService.latestClosedMonth()).thenReturn(YearMonth.of(2026, 6)); // July first
        assertThat(codes(advise(sep3))).doesNotContain("advisor.s.closeMonth");

        when(monthCloseService.latestClosedMonth()).thenReturn(null);
        when(transactionRepository.existsByTransactionDateLessThanEqual(LocalDate.of(2026, 8, 31))).thenReturn(false);
        assertThat(codes(advise(sep3))).doesNotContain("advisor.s.closeMonth");

        when(transactionRepository.existsByTransactionDateLessThanEqual(LocalDate.of(2026, 8, 31))).thenReturn(true);
        assertThat(codes(advise(sep3))).contains("advisor.s.closeMonth");
    }

    // ── Encouragement ─────────────────────────────────────────────────────────

    @Test
    void withNoGoalItSuggestsStartingOne() {
        assertThat(find(advise(SEP_18), "advisor.s.addGoal").getKind()).isEqualTo("IDEA");

        when(investmentRepository.findAll()).thenReturn(List.of(goal(5, "Home", "200000000", "0")));
        assertThat(codes(advise(SEP_18))).doesNotContain("advisor.s.addGoal");
    }

    /**
     * The walk leaves 2,170,000 at its horizon at the owner's own pace → half, rounded down to
     * 10,000 → 1,080,000 towards the first goal still short of its target.
     */
    @Test
    void spareMoneyIsHalfSuggestedForTheFirstGoalStillShortOfItsTarget() {
        wallets = new ArrayList<>(List.of(card("MinCon", "3000000")));
        salaryRecorded("8000000");
        everythingPaid();
        spare("2170000");
        when(investmentRepository.findAll()).thenReturn(List.of(
                goal(4, "Phone", "10000000", "10000000"),   // reached
                goal(5, "Home", "200000000", "5000000")));

        Suggestion extra = find(advise(LocalDate.of(2026, 9, 20)), "advisor.s.extraToGoal");

        assertThat(extra.getAmount()).isEqualByComparingTo("1080000");
        assertThat(extra.getRefId()).isEqualTo(5L);
        assertThat(extra.getBucket()).isEqualTo("SAVINGS");
        assertThat(extra.getParams()).isEqualTo(Map.of("name", "Home"));
        assertThat(extra.getText()).isEqualTo("You have about 2 170 000 UZS more than you need until 6 November"
                + " at your usual pace. Put 1 080 000 UZS towards Home?");
    }

    @Test
    void withoutAGoalSpareMoneyBuildsTheEmergencyFundFirstThenInvestments() {
        wallets = new ArrayList<>(List.of(card("MinCon", "3000000")));
        salaryRecorded("8000000");
        everythingPaid();
        spare("3000000");
        LocalDate sep20 = LocalDate.of(2026, 9, 20);

        assertThat(find(advise(sep20), "advisor.s.extraToEmergency").getBucket()).isEqualTo("EMERGENCY");

        when(emergencyRepository.count()).thenReturn(3L);
        Suggestion invest = find(advise(sep20), "advisor.s.extraToInvestments");
        assertThat(invest.getBucket()).isEqualTo("INVESTMENTS");
        assertThat(invest.getAmount()).isEqualByComparingTo("1500000");
    }

    @Test
    void noSpareMoneyAdviceBeforeTheSalaryOnAStaleBalanceWithBillsUnpaidOrWithoutAPace() {
        wallets = new ArrayList<>(List.of(card("MinCon", "9000000")));
        everythingPaid();
        spare("5000000");
        LocalDate sep20 = LocalDate.of(2026, 9, 20);
        String[] extras = {"advisor.s.extraToGoal", "advisor.s.extraToEmergency", "advisor.s.extraToInvestments"};

        // The salary is still to come: it is not money to invest yet.
        assertThat(codes(advise(sep20))).doesNotContain(extras);

        salaryRecorded("8000000");
        daysSinceChecked = 12; // the balance is too old to call anything spare
        assertThat(codes(advise(sep20))).doesNotContain(extras);

        daysSinceChecked = null; // never checked
        assertThat(codes(advise(sep20))).doesNotContain(extras);

        daysSinceChecked = 1;
        pending.add(sub(2, "1Fit", "558000", "0"));
        assertThat(codes(advise(sep20))).doesNotContain(extras);

        pending.clear();
        assertThat(codes(advise(sep20))).containsAnyOf(extras);

        // No pace yet (under a week of data): nothing is known about what living costs.
        when(dailyAdviceService.compute(any())).thenReturn(new DailyAdviceService.Result(
                daily().paceDaily(null).paceFrom(null).paceTo(null).build(), null));
        assertThat(codes(advise(sep20))).doesNotContain(extras);

        spare("199999"); // below EXTRA_MIN
        assertThat(codes(advise(sep20))).doesNotContain(extras);
    }

    /**
     * The owner's 23 September in miniature: 9M in the wallets, the month paid for, the salary in,
     * the wallets just checked — the month-only view calls most of it free. At the owner's pace the
     * money does not even last to the next rent, so nothing is offered for investing.
     */
    @Test
    void aBigBalanceIsNotSpareWhenTheOwnersPaceWillSpendIt() {
        wallets = new ArrayList<>(List.of(card("MinCon", "9000000")));
        salaryRecorded("8000000");
        everythingPaid();
        when(emergencyRepository.count()).thenReturn(1L);
        spare("-15207000");

        AdvisorResponse r = advise(LocalDate.of(2026, 9, 23));

        assertThat(r.getFree()).isEqualByComparingTo("9000000");
        assertThat(codes(r)).doesNotContain("advisor.s.extraToInvestments", "advisor.s.extraToEmergency",
                "advisor.s.extraToGoal");
    }

    /** While the walk says short or running out, nothing is spare — whatever the surplus reads. */
    @Test
    void noSpareMoneyIdeaWhileTheWalkSaysShortOrRunningOut() {
        wallets = new ArrayList<>(List.of(card("MinCon", "9000000")));
        salaryRecorded("8000000");
        everythingPaid();
        when(emergencyRepository.count()).thenReturn(1L);
        LocalDate sep20 = LocalDate.of(2026, 9, 20);
        String[] extras = {"advisor.s.extraToGoal", "advisor.s.extraToEmergency", "advisor.s.extraToInvestments"};

        when(dailyAdviceService.compute(any())).thenReturn(new DailyAdviceService.Result(
                daily().runsOutOn(LocalDate.of(2026, 10, 10)).build(), new BigDecimal("5000000")));
        assertThat(codes(advise(sep20))).doesNotContain(extras);

        when(dailyAdviceService.compute(any())).thenReturn(new DailyAdviceService.Result(
                daily().shortBy(ShortBy.builder().date(LocalDate.of(2026, 10, 5)).amount(new BigDecimal("1500000"))
                        .build()).build(), new BigDecimal("5000000")));
        assertThat(codes(advise(sep20))).doesNotContain(extras);

        spare("5000000");   // neither: offered
        assertThat(codes(advise(sep20))).contains("advisor.s.extraToInvestments");
    }

    // ── No income yet ─────────────────────────────────────────────────────────

    @Test
    void withoutAStableIncomeItAsksForThatAndDoesNotPretendToKnowWhatIsFree() {
        missingIncome = true;
        lines = new ArrayList<>();

        AdvisorResponse r = advise(SEP_18);

        assertThat(r.isMissingStableIncome()).isTrue();
        assertThat(r.getFree()).isNull();
        assertThat(r.getDaily()).isNull();
        assertThat(r.getSavingsThisMonth()).isEmpty();
        assertThat(r.getHave()).isEqualByComparingTo("2010000");
        assertThat(codes(r)).first().isEqualTo("advisor.s.setIncome");
        assertThat(codes(r)).doesNotContain("advisor.s.addGoal");
        verify(dailyAdviceService, never()).compute(any());
    }
}
