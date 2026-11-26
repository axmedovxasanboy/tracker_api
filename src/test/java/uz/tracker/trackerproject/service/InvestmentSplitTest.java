package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.InvestmentRequest;
import uz.tracker.trackerproject.dto.request.InvestmentSplitRequest;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.SavingsRow;
import uz.tracker.trackerproject.dto.response.InvestmentSplitResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.repository.*;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * "This month's split" (Batch 81): the month's Investments figure split across the accounts — equal
 * until the owner sets shares, then by the typed amounts as weights; whole 1.000s adding up exactly.
 */
class InvestmentSplitTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    private final Map<Long, Investment> holdings = new LinkedHashMap<>();
    private final List<Transaction> txs = new ArrayList<>();
    private InvestmentRepository investmentRepository;
    private AdvisorService advisor;
    private OverviewService overview;
    private InvestmentSplitService service;
    /** The advisor's Investments row: target, carried, paid. */
    private BigDecimal target = new BigDecimal("1000000"), carried = BigDecimal.ZERO, paid = BigDecimal.ZERO;
    private long nextTx = 100;

    @BeforeEach
    void setUp() {
        investmentRepository = mock(InvestmentRepository.class);
        when(investmentRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(holdings.get((Long) inv.getArgument(0))));
        when(investmentRepository.findAll()).thenAnswer(inv -> new ArrayList<>(holdings.values()));
        when(investmentRepository.findAllByOrderByPurchaseDateDesc()).thenAnswer(inv -> new ArrayList<>(holdings.values()));
        when(investmentRepository.save(any(Investment.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                eq(TransactionSubType.INVESTMENT), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    LocalDate from = inv.getArgument(1), to = inv.getArgument(2);
                    return txs.stream().filter(t -> !t.getTransactionDate().isBefore(from) && !t.getTransactionDate().isAfter(to)).toList();
                });

        advisor = mock(AdvisorService.class);
        when(advisor.savingsThisMonth(any(LocalDate.class))).thenAnswer(inv -> List.of(
                SavingsRow.builder().bucket("DONATION").target(new BigDecimal("500000")).carried(BigDecimal.ZERO)
                        .paid(BigDecimal.ZERO).remaining(new BigDecimal("500000")).build(),
                SavingsRow.builder().bucket("INVESTMENTS").target(target).carried(carried).paid(paid)
                        .remaining(target.add(carried).subtract(paid).max(BigDecimal.ZERO)).build()));
        overview = mock(OverviewService.class);
        when(overview.bucketOf(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            return t.getAllocationBucket() != null ? t.getAllocationBucket() : AllocationBucket.INVESTMENTS;
        });

        service = new InvestmentSplitService(investmentRepository, transactions, advisor, overview) {
            @Override LocalDate today() { return TODAY; }
        };

        // The owner's example: IMAN 2, Asaxiy 3, Open Bank 3, Hayot bank 3 — 11 accounts.
        long id = 1;
        for (int k = 1; k <= 2; k++) holding(id++, "IMAN " + k, "IMAN", "10000000");
        for (int k = 1; k <= 3; k++) holding(id++, "Asaxiy " + k, "Asaxiy", "10000000");
        for (int k = 1; k <= 3; k++) holding(id++, "Open Bank " + k, "Open Bank", "10000000");
        for (int k = 1; k <= 3; k++) holding(id++, "Hayot " + k, "Hayot bank", "10000000");
    }

    private Investment holding(long id, String name, String broker, String invested) {
        Investment i = new Investment();
        i.setId(id);
        i.setName(name);
        i.setBroker(broker);
        i.setType(InvestmentType.BUSINESS);
        i.setCurrency(Currency.UZS);
        i.setInvestedAmount(new BigDecimal(invested));
        i.setPurchaseDate(LocalDate.of(2026, 6, 1));
        holdings.put(id, i);
        return i;
    }

    private void putIn(long investmentId, String amount, LocalDate date, String bucket) {
        Transaction t = new Transaction();
        t.setId(nextTx++);
        t.setSubType(TransactionSubType.INVESTMENT);
        t.setAmount(new BigDecimal(amount));
        t.setTransactionDate(date);
        t.setInvestmentId(investmentId);
        t.setAllocationBucket(bucket);
        txs.add(t);
    }

    private static InvestmentSplitRequest weights(Object... idAndWeight) {
        InvestmentSplitRequest r = new InvestmentSplitRequest();
        List<InvestmentSplitRequest.Weight> list = new ArrayList<>();
        for (int k = 0; k < idAndWeight.length; k += 2) {
            InvestmentSplitRequest.Weight w = new InvestmentSplitRequest.Weight();
            w.setInvestmentId(((Number) idAndWeight[k]).longValue());
            w.setWeight(new BigDecimal(idAndWeight[k + 1].toString()));
            list.add(w);
        }
        r.setWeights(list);
        return r;
    }

    private static BigDecimal sum(InvestmentSplitResponse r) {
        return r.getRows().stream().map(InvestmentSplitResponse.Row::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static InvestmentSplitResponse.Row row(InvestmentSplitResponse r, long id) {
        return r.getRows().stream().filter(x -> x.getInvestmentId() == id).findFirst().orElseThrow();
    }

    /** 1.000.000 over 11 accounts: ten get 91.000, one 90.000 — whole 1.000s adding up exactly. */
    @Test
    void equalSplitInWholeThousands() {
        InvestmentSplitResponse r = service.get(null);

        assertThat(r.getMonth()).isEqualTo("2026-11");
        assertThat(r.isCustom()).isFalse();
        assertThat(r.getTarget()).isEqualByComparingTo("1000000");
        assertThat(r.getRows()).hasSize(11);
        assertThat(sum(r)).isEqualByComparingTo("1000000");
        assertThat(r.getRows().stream().filter(x -> x.getAmount().compareTo(new BigDecimal("91000")) == 0)).hasSize(10);
        assertThat(r.getRows().stream().filter(x -> x.getAmount().compareTo(new BigDecimal("90000")) == 0)).hasSize(1);
        assertThat(r.getRows()).allSatisfy(x -> assertThat(x.getPercent()).isEqualByComparingTo("9.1"));
        // Ordered by provider, then name.
        assertThat(r.getRows().get(0).getBroker()).isEqualTo("Asaxiy");
        assertThat(r.getRows().get(10).getName()).isEqualTo("Open Bank 3");
    }

    /** The month's figure is the advisor's Investments row: its target plus what earlier months carried. */
    @Test
    void targetIncludesTheCarriedPart() {
        target = new BigDecimal("800000");
        carried = new BigDecimal("200500");

        InvestmentSplitResponse r = service.get("2026-11");

        assertThat(r.getTarget()).isEqualByComparingTo("1000500");
        assertThat(sum(r)).isEqualByComparingTo("1000500");
        // The leftover under 1.000 sits on the first row.
        assertThat(r.getRows().get(0).getAmount().remainder(new BigDecimal("1000"))).isEqualByComparingTo("500");
    }

    /** Typed amounts become weights; next month's different figure is split by the same shares. */
    @Test
    void customWeightsScaleWithTheTarget() {
        InvestmentSplitResponse r = service.put(weights(1, 500000, 2, 300000, 6, 200000));

        assertThat(r.isCustom()).isTrue();
        assertThat(row(r, 1).getAmount()).isEqualByComparingTo("500000");
        assertThat(row(r, 2).getAmount()).isEqualByComparingTo("300000");
        assertThat(row(r, 6).getAmount()).isEqualByComparingTo("200000");
        assertThat(row(r, 3).getAmount()).isEqualByComparingTo("0");
        assertThat(row(r, 1).getPercent()).isEqualByComparingTo("50.0");
        assertThat(holdings.get(3L).getSplitWeight()).isEqualByComparingTo("0");

        target = new BigDecimal("2000000");
        InvestmentSplitResponse next = service.get("2026-12");
        assertThat(row(next, 1).getAmount()).isEqualByComparingTo("1000000");
        assertThat(row(next, 2).getAmount()).isEqualByComparingTo("600000");
        assertThat(row(next, 6).getAmount()).isEqualByComparingTo("400000");
        assertThat(sum(next)).isEqualByComparingTo("2000000");
    }

    /** Under a custom split a new account starts at 0; DELETE goes back to equal. */
    @Test
    void newAccountStartsAtZeroAndResetIsEqual() {
        service.put(weights(1, 1, 2, 1));
        holding(12, "IMAN 3", "IMAN", "5000000");

        InvestmentSplitResponse r = service.get(null);
        assertThat(row(r, 12).getAmount()).isEqualByComparingTo("0");
        assertThat(row(r, 12).getPercent()).isEqualByComparingTo("0");
        assertThat(row(r, 1).getAmount()).isEqualByComparingTo("500000");

        InvestmentSplitResponse equal = service.reset();
        assertThat(equal.isCustom()).isFalse();
        assertThat(holdings.values()).allSatisfy(i -> assertThat(i.getSplitWeight()).isNull());
        assertThat(equal.getRows()).hasSize(12);
        assertThat(sum(equal)).isEqualByComparingTo("1000000");
    }

    /** Paid per account = this month's Investments put-ins; last month's, goals' and own savings' are not. */
    @Test
    void paidPerAccountCountsThisMonthsInvestmentPutIns() {
        paid = new BigDecimal("150000");
        putIn(1, "50000", LocalDate.of(2026, 11, 3), null);
        putIn(1, "40000", LocalDate.of(2026, 11, 20), AllocationBucket.INVESTMENTS);
        putIn(1, "70000", LocalDate.of(2026, 10, 28), null);                  // last month
        putIn(2, "30000", LocalDate.of(2026, 11, 5), AllocationBucket.CUSTOM); // an own saving's money
        putIn(99, "60000", LocalDate.of(2026, 11, 6), null);                   // not a listed account

        InvestmentSplitResponse r = service.get(null);

        assertThat(row(r, 1).getPaid()).isEqualByComparingTo("90000");
        assertThat(row(r, 1).getLeft()).isEqualByComparingTo(row(r, 1).getAmount().subtract(new BigDecimal("90000")));
        assertThat(row(r, 2).getPaid()).isEqualByComparingTo("0");
        assertThat(r.getPaid()).isEqualByComparingTo("150000");
        assertThat(r.getPaidOutside()).isEqualByComparingTo("60000");
        assertThat(r.getLeft()).isEqualByComparingTo("850000");
    }

    /** No Investments row (no income or nothing asked): target 0, every amount 0. */
    @Test
    void nothingAskedMeansZero() {
        when(advisor.savingsThisMonth(any(LocalDate.class))).thenReturn(List.of());
        when(overview.computePaidThisMonth(any(YearMonth.class), eq(Currency.UZS))).thenReturn(
                new OverviewService.BucketPaid(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("20000"), BigDecimal.ZERO, BigDecimal.ZERO));

        InvestmentSplitResponse r = service.get(null);

        assertThat(r.getTarget()).isEqualByComparingTo("0");
        assertThat(r.getPaid()).isEqualByComparingTo("20000");
        assertThat(r.getRows()).allSatisfy(x -> assertThat(x.getAmount()).isEqualByComparingTo("0"));
        assertThat(r.getRows()).allSatisfy(x -> assertThat(x.getPercent()).isEqualByComparingTo("9.1"));
    }

    @Test
    void refusesNonAccountsNegativeAndAllZero() {
        Investment fund = holding(20, "IMAN (Emergency)", "IMAN", "1000000");
        fund.setEmergencyFund(true);
        Investment goal = holding(21, "iPhone", null, "1000000");
        goal.setSavingsGoal(true);

        assertThatThrownBy(() -> service.put(weights(20, 100))).hasMessageContaining("not an investment account");
        assertThatThrownBy(() -> service.put(weights(21, 100))).hasMessageContaining("not an investment account");
        assertThatThrownBy(() -> service.put(weights(1, -5))).hasMessageContaining("negative");
        assertThatThrownBy(() -> service.put(weights(1, 0, 2, 0))).hasMessageContaining("at least one account");
        assertThat(holdings.values()).allSatisfy(i -> assertThat(i.getSplitWeight()).isNull());
        // Neither shows up in the split.
        assertThat(service.get(null).getRows()).extracting(InvestmentSplitResponse.Row::getInvestmentId)
                .doesNotContain(20L, 21L);
    }

    /** The bot edits by echoing a holding's fields: the share stays; made the emergency fund, it goes. */
    @Test
    void echoEditKeepsTheShareAndNonPlainDropsIt() throws Exception {
        service.put(weights(1, 300000, 2, 700000));
        TransactionRepository tx = mock(TransactionRepository.class);
        FinanceService finance = new FinanceService(mock(DebtRepository.class), mock(LoanGivenRepository.class), mock(LoanTakenRepository.class),
                mock(BankLoanRepository.class), mock(MonthlyPaymentRepository.class), mock(DonationRepository.class),
                investmentRepository, mock(CategoryRepository.class), tx,
                mock(CardRepository.class), mock(MarkPaidRepository.class), mock(CardService.class),
                mock(MonthCloseService.class), mock(SettingsService.class), mock(CounterpartyService.class));
        InvestmentGoalRepository goals = mock(InvestmentGoalRepository.class);
        when(goals.findAll()).thenReturn(List.of());
        Field f = FinanceService.class.getDeclaredField("investmentGoalRepository");
        f.setAccessible(true);
        f.set(finance, goals);

        finance.updateInvestment(1L, echo(holdings.get(1L)));
        assertThat(holdings.get(1L).getSplitWeight()).isEqualByComparingTo("300000");

        InvestmentRequest r = echo(holdings.get(2L));
        r.setEmergencyFund(true);
        finance.updateInvestment(2L, r);
        assertThat(holdings.get(2L).getSplitWeight()).isNull();
    }

    private static InvestmentRequest echo(Investment i) {
        InvestmentRequest r = new InvestmentRequest();
        r.setName(i.getName());
        r.setType(i.getType());
        r.setInvestedAmount(i.getInvestedAmount());
        r.setCurrency(i.getCurrency());
        r.setPurchaseDate(i.getPurchaseDate());
        r.setBroker(i.getBroker());
        return r;
    }
}
