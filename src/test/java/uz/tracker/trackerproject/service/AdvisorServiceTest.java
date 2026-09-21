package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AdvisorResponse;
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
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
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
import static org.mockito.Mockito.when;

/**
 * The advisor adds up figures the other services already compute, so these tests hand it those
 * figures directly and pin what it does with them: the four totals, the order of the advice, and
 * when it stays quiet. The owner's own numbers are used where they fit — 8M salary, rent 4.2M,
 * 1Fit 558K, 10/5/15 of a 3.242M left balance.
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
        service = new AdvisorService(overviewService, walletCheckInService, monthCloseService,
                transactionRepository, loanGivenRepository, investmentRepository, emergencyRepository);

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

    @Test
    void aMonthThatAsksForMoreThanThereIsSaysSo() {
        wallets.clear();
        pending.add(sub(1, "Kvartira Arenda", "4200000", "0"));
        salaryRecorded("8000000"); // in, and already spent: nothing left in the wallets

        AdvisorResponse r = advise(SEP_18);

        assertThat(r.getFree()).isEqualByComparingTo("-5172600");
        assertThat(find(r, "advisor.s.short").getAmount()).isEqualByComparingTo("5172600");
        assertThat(find(r, "advisor.s.short").getKind()).isEqualTo("WARN");
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
     * Sep 20, 11 days left. The plan leaves 3,242,000 × 70% = 2,269,400 a month to live on, so
     * 832,113.33 for the rest of September. 3,000,000 free − that = 2,167,886.67 spare → half,
     * rounded down to 10,000 → 1,080,000 towards the goal.
     */
    @Test
    void spareMoneyIsHalfSuggestedForTheFirstGoalStillShortOfItsTarget() {
        wallets = new ArrayList<>(List.of(card("MinCon", "3000000")));
        salaryRecorded("8000000");
        everythingPaid();
        when(investmentRepository.findAll()).thenReturn(List.of(
                goal(4, "Phone", "10000000", "10000000"),   // reached
                goal(5, "Home", "200000000", "5000000")));

        Suggestion extra = find(advise(LocalDate.of(2026, 9, 20)), "advisor.s.extraToGoal");

        assertThat(extra.getAmount()).isEqualByComparingTo("1080000");
        assertThat(extra.getRefId()).isEqualTo(5L);
        assertThat(extra.getBucket()).isEqualTo("SAVINGS");
        assertThat(extra.getParams()).isEqualTo(Map.of("name", "Home"));
    }

    @Test
    void withoutAGoalSpareMoneyBuildsTheEmergencyFundFirstThenInvestments() {
        wallets = new ArrayList<>(List.of(card("MinCon", "3000000")));
        salaryRecorded("8000000");
        everythingPaid();
        LocalDate sep20 = LocalDate.of(2026, 9, 20);

        assertThat(find(advise(sep20), "advisor.s.extraToEmergency").getBucket()).isEqualTo("EMERGENCY");

        when(emergencyRepository.count()).thenReturn(3L);
        assertThat(find(advise(sep20), "advisor.s.extraToInvestments").getBucket()).isEqualTo("INVESTMENTS");
    }

    @Test
    void noSpareMoneyAdviceBeforeTheSalaryOnAStaleBalanceOrWithBillsUnpaid() {
        wallets = new ArrayList<>(List.of(card("MinCon", "9000000")));
        everythingPaid();
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
    }

    // ── No income yet ─────────────────────────────────────────────────────────

    @Test
    void withoutAStableIncomeItAsksForThatAndDoesNotPretendToKnowWhatIsFree() {
        missingIncome = true;
        lines = new ArrayList<>();

        AdvisorResponse r = advise(SEP_18);

        assertThat(r.isMissingStableIncome()).isTrue();
        assertThat(r.getFree()).isNull();
        assertThat(r.getHave()).isEqualByComparingTo("2010000");
        assertThat(codes(r)).first().isEqualTo("advisor.s.setIncome");
        assertThat(codes(r)).doesNotContain("advisor.s.addGoal");
    }
}
