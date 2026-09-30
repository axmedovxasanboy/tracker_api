package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Flow;
import uz.tracker.trackerproject.dto.response.TransactionResponse;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionFlow;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code TransactionResponse.flow}: one classification, decided in {@link TransactionFlows#of}, read
 * by History, the bot and Analytics alike. The last test holds the two together: what
 * {@code GET /analytics} totals is what the rows' own flows add up to.
 */
class TransactionFlowTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private AnalyticsFixture f;
    private final List<Transaction> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        f = new AnalyticsFixture();
    }

    private Transaction row(int day, TransactionType type, TransactionSubType subType, String amount) {
        Transaction t = f.ledger.add(SEP.atDay(day), type, subType, amount);
        rows.add(t);
        return t;
    }

    /** The flow as a client receives it. */
    private static TransactionFlow flow(Transaction t) {
        TransactionFlow onTheWire = TransactionResponse.from(t).getFlow();
        assertThat(onTheWire).isSameAs(TransactionFlows.of(t)).isNotNull();
        return onTheWire;
    }

    @Test
    void incomeIsEarned_unlessItIsBorrowedReturnedTakenFromSavingsOrACorrection() {
        assertThat(flow(row(1, TransactionType.INCOME, TransactionSubType.REGULAR_INCOME, "1"))).isEqualTo(TransactionFlow.EARNED);
        assertThat(flow(row(1, TransactionType.INCOME, null, "1"))).isEqualTo(TransactionFlow.EARNED);
        assertThat(flow(row(1, TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "1"))).isEqualTo(TransactionFlow.BORROWED);
        assertThat(flow(row(1, TransactionType.INCOME, TransactionSubType.LOAN_RETURNED_TO_ME, "1"))).isEqualTo(TransactionFlow.RETURNED);
        assertThat(flow(row(1, TransactionType.INCOME, TransactionSubType.INVESTMENT_WITHDRAWAL, "1"))).isEqualTo(TransactionFlow.FROM_SAVINGS);
        assertThat(flow(row(1, TransactionType.INCOME, TransactionSubType.EVERYDAY_SPENDING, "1"))).isEqualTo(TransactionFlow.CORRECTION);
    }

    @Test
    void anExpenseIsLentALoanPaymentABillOrEverydaySpending() {
        assertThat(flow(row(1, TransactionType.EXPENSE, TransactionSubType.LOAN_GIVEN, "1"))).isEqualTo(TransactionFlow.LENT);
        assertThat(flow(row(1, TransactionType.EXPENSE, TransactionSubType.BANK_LOAN_PAYMENT, "1"))).isEqualTo(TransactionFlow.LOAN_PAYMENT);
        assertThat(flow(row(1, TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "1"))).isEqualTo(TransactionFlow.LOAN_PAYMENT);
        Transaction bill = row(1, TransactionType.EXPENSE, TransactionSubType.REGULAR_EXPENSE, "1");
        bill.setMonthlyPaymentId(3L);
        assertThat(flow(bill)).isEqualTo(TransactionFlow.BILL);
        assertThat(flow(row(1, TransactionType.EXPENSE, TransactionSubType.REGULAR_EXPENSE, "1"))).isEqualTo(TransactionFlow.EVERYDAY);
        assertThat(flow(row(1, TransactionType.EXPENSE, null, "1"))).isEqualTo(TransactionFlow.EVERYDAY);
        // A wallet check that found less: everyday too — a client tells it apart by the sub-type, as today.
        Transaction check = row(1, TransactionType.EXPENSE, TransactionSubType.EVERYDAY_SPENDING, "1");
        assertThat(flow(check)).isEqualTo(TransactionFlow.EVERYDAY);
        assertThat(TransactionResponse.from(check).getSubType()).isEqualTo(TransactionSubType.EVERYDAY_SPENDING);
    }

    /** Donations are Given, not Saved: a saving whose bucket is DONATION. The other three buckets are Saved. */
    @Test
    void aDonationIsGiven_everyOtherSavingIsSaved() {
        Transaction donation = row(1, TransactionType.EXPENSE, TransactionSubType.DONATION, "1");
        donation.setAllocationBucket(AllocationBucket.DONATION);
        assertThat(flow(donation)).isEqualTo(TransactionFlow.GIVEN);
        // A row from before the bucket was recorded: its sub-type names it.
        assertThat(flow(row(1, TransactionType.EXPENSE, TransactionSubType.DONATION, "1"))).isEqualTo(TransactionFlow.GIVEN);

        Transaction emergency = row(1, TransactionType.EXPENSE, TransactionSubType.EMERGENCY_CONTRIBUTION, "1");
        emergency.setAllocationBucket(AllocationBucket.EMERGENCY);
        Transaction investment = row(1, TransactionType.EXPENSE, TransactionSubType.INVESTMENT, "1");
        investment.setAllocationBucket(AllocationBucket.INVESTMENTS);
        Transaction goal = row(1, TransactionType.EXPENSE, TransactionSubType.INVESTMENT, "1");
        goal.setAllocationBucket(AllocationBucket.SAVINGS);
        Transaction stocks = row(1, TransactionType.EXPENSE, TransactionSubType.STOCK_PURCHASE, "1");
        stocks.setAllocationBucket(AllocationBucket.STOCKS);
        Transaction old = row(1, TransactionType.EXPENSE, TransactionSubType.INVESTMENT, "1");   // no bucket recorded
        assertThat(List.of(emergency, investment, goal, stocks, old)).allMatch(t -> flow(t) == TransactionFlow.SAVED);
    }

    @Test
    void aMoveBetweenOwnWalletsIsATransfer_whicheverHalfItIs() {
        Transaction out = row(1, TransactionType.EXPENSE, TransactionSubType.TRANSFER_OUT, "1");
        Transaction in = row(1, TransactionType.INCOME, TransactionSubType.TRANSFER_IN, "1");
        out.setTransferPairId(out.getId());
        in.setTransferPairId(out.getId());
        // A cash ↔ card move: plain sub-types, only the pair id says so.
        Transaction pairedOut = row(1, TransactionType.EXPENSE, TransactionSubType.REGULAR_EXPENSE, "1");
        Transaction pairedIn = row(1, TransactionType.INCOME, TransactionSubType.REGULAR_INCOME, "1");
        pairedOut.setTransferPairId(pairedOut.getId());
        pairedIn.setTransferPairId(pairedOut.getId());
        Transaction legacy = row(1, TransactionType.INCOME, TransactionSubType.TRANSFER_IN, "1");   // no pair id

        assertThat(List.of(out, in, pairedOut, pairedIn, legacy)).allMatch(t -> flow(t) == TransactionFlow.TRANSFER);
    }

    @Test
    void firstMatchWins_andARowInAForeignPotHasAFlowToo() {
        Transaction donationOnABill = row(1, TransactionType.EXPENSE, TransactionSubType.DONATION, "1");
        donationOnABill.setMonthlyPaymentId(3L);
        assertThat(flow(donationOnABill)).isEqualTo(TransactionFlow.GIVEN);
        Transaction repaymentOnABill = row(1, TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "1");
        repaymentOnABill.setMonthlyPaymentId(3L);
        assertThat(flow(repaymentOnABill)).isEqualTo(TransactionFlow.LOAN_PAYMENT);
        Transaction billByCheck = row(1, TransactionType.EXPENSE, TransactionSubType.EVERYDAY_SPENDING, "1");
        billByCheck.setMonthlyPaymentId(3L);
        assertThat(flow(billByCheck)).isEqualTo(TransactionFlow.BILL);

        Transaction dollars = row(1, TransactionType.EXPENSE, TransactionSubType.REGULAR_EXPENSE, "100");
        dollars.setCurrency(Currency.USD);
        assertThat(flow(dollars)).isEqualTo(TransactionFlow.EVERYDAY);
    }

    /**
     * What Analytics totals is what the rows' flows add up to — the page and History sum the same
     * classification. (Analytics leaves the foreign pot out; so does this sum.)
     */
    @Test
    void analyticsTotalsAreTheSumsByFlowOverTheSameRows() {
        Category salary = f.category(10, "Salary", null);
        row(5, TransactionType.INCOME, TransactionSubType.REGULAR_INCOME, "7000000").setCategory(salary);
        row(6, TransactionType.INCOME, null, "90000");
        row(7, TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "1955000");
        row(8, TransactionType.INCOME, TransactionSubType.LOAN_RETURNED_TO_ME, "700000");
        row(9, TransactionType.INCOME, TransactionSubType.INVESTMENT_WITHDRAWAL, "250000");
        row(10, TransactionType.INCOME, TransactionSubType.EVERYDAY_SPENDING, "120000");
        row(11, TransactionType.EXPENSE, TransactionSubType.LOAN_GIVEN, "1000000");
        row(12, TransactionType.EXPENSE, TransactionSubType.DONATION, "800000").setAllocationBucket(AllocationBucket.DONATION);
        row(12, TransactionType.EXPENSE, TransactionSubType.DONATION, "50000");                       // no bucket recorded
        row(13, TransactionType.EXPENSE, TransactionSubType.EMERGENCY_CONTRIBUTION, "720000").setAllocationBucket(AllocationBucket.EMERGENCY);
        row(13, TransactionType.EXPENSE, TransactionSubType.INVESTMENT, "3000000").setAllocationBucket(AllocationBucket.INVESTMENTS);
        row(13, TransactionType.EXPENSE, TransactionSubType.INVESTMENT, "400000").setAllocationBucket(AllocationBucket.SAVINGS);
        row(14, TransactionType.EXPENSE, TransactionSubType.BANK_LOAN_PAYMENT, "400000");
        row(14, TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "1155000");
        row(15, TransactionType.EXPENSE, TransactionSubType.REGULAR_EXPENSE, "4200000").setMonthlyPaymentId(3L);
        row(16, TransactionType.EXPENSE, TransactionSubType.REGULAR_EXPENSE, "2100000");
        row(17, TransactionType.EXPENSE, null, "350000");
        row(18, TransactionType.EXPENSE, TransactionSubType.EVERYDAY_SPENDING, "2168000");
        Transaction out = row(19, TransactionType.EXPENSE, TransactionSubType.TRANSFER_OUT, "1000000");
        Transaction in = row(19, TransactionType.INCOME, TransactionSubType.TRANSFER_IN, "1000000");
        out.setTransferPairId(out.getId());
        in.setTransferPairId(out.getId());
        row(20, TransactionType.EXPENSE, TransactionSubType.REGULAR_EXPENSE, "100").setCurrency(Currency.USD);

        Map<TransactionFlow, BigDecimal> sum = new EnumMap<>(TransactionFlow.class);
        for (TransactionFlow flow : TransactionFlow.values()) sum.put(flow, BigDecimal.ZERO);
        int counted = 0;
        for (Transaction t : rows) {
            if (t.getCurrency() != Currency.UZS) continue;
            TransactionFlow flow = TransactionResponse.from(t).getFlow();
            sum.merge(flow, t.getAmount(), BigDecimal::add);
            if (flow != TransactionFlow.TRANSFER) counted++;
        }

        Flow totals = f.analytics.analytics(SEP, SEP, TODAY).getTotals();

        assertThat(totals.getEarned()).isEqualByComparingTo(sum.get(TransactionFlow.EARNED)).isEqualByComparingTo("7090000");
        assertThat(totals.getBorrowed()).isEqualByComparingTo(sum.get(TransactionFlow.BORROWED));
        assertThat(totals.getReturned()).isEqualByComparingTo(sum.get(TransactionFlow.RETURNED));
        assertThat(totals.getFromSavings()).isEqualByComparingTo(sum.get(TransactionFlow.FROM_SAVINGS));
        assertThat(totals.getLent()).isEqualByComparingTo(sum.get(TransactionFlow.LENT));
        assertThat(totals.getLoanPayments()).isEqualByComparingTo(sum.get(TransactionFlow.LOAN_PAYMENT));
        assertThat(totals.getBills()).isEqualByComparingTo(sum.get(TransactionFlow.BILL));
        // Everyday is what was spent less what a wallet check put right.
        assertThat(totals.getEveryday()).isEqualByComparingTo(
                sum.get(TransactionFlow.EVERYDAY).subtract(sum.get(TransactionFlow.CORRECTION))).isEqualByComparingTo("4498000");
        // Analytics' "saved" is the month's whole set-aside: Saved + Given, with Given as its donation part.
        assertThat(totals.getSavedDonation()).isEqualByComparingTo(sum.get(TransactionFlow.GIVEN)).isEqualByComparingTo("850000");
        assertThat(totals.getSaved().subtract(totals.getSavedDonation())).isEqualByComparingTo(sum.get(TransactionFlow.SAVED))
                .isEqualByComparingTo("4120000");
        assertThat(totals.getCount()).isEqualTo(counted).isEqualTo(18);
        assertThat(sum.get(TransactionFlow.TRANSFER)).isEqualByComparingTo("2000000");   // in no total
    }
}
