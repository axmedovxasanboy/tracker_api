package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The owner's carry rule (2026-09-27), per bucket from the tracking start:
 * due = target + carried; carried next month = max(0, due − paid). An overpayment never carries.
 * A 7M stable income with no bills and no debt is Level 1.1: donation 700,000, emergency 350,000,
 * investments 1,050,000 a month.
 */
class CarryOverTest {

    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final YearMonth NOV = YearMonth.of(2026, 11);

    private final List<BankLoan> banks = new ArrayList<>();
    private final List<Debt> debts = new ArrayList<>();
    private TransactionLedger ledger;
    private OverviewService service;

    @BeforeEach
    void setUp() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        SettingsService settingsService = mock(SettingsService.class);
        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("7000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(AUG.atDay(1));
        when(settingsService.getOrCreate()).thenReturn(settings);
        when(bankLoanRepository.findAll()).thenReturn(banks);
        when(debtRepository.findAll()).thenReturn(debts);

        ledger = new TransactionLedger(transactionRepository);
        service = new OverviewService(transactionRepository, mock(MonthlyPaymentRepository.class),
                bankLoanRepository, mock(LoanTakenRepository.class), debtRepository,
                mock(DonationRepository.class), mock(InvestmentRepository.class),
                mock(LevelAllocationRuleRepository.class), mock(LevelConfigRepository.class),
                mock(MarkPaidRepository.class), settingsService, mock(CategoryRepository.class));
    }

    private void invest(YearMonth month, String amount) {
        ledger.add(month.atDay(10), TransactionType.EXPENSE, TransactionSubType.INVESTMENT, amount);
    }

    private BigDecimal carried(YearMonth into, String bucket) {
        return service.carriedInto(into).get(bucket);
    }

    /**
     * The chain over three months: August's 1,050,000 unpaid is carried into September; September
     * pays 1,500,000 of its 2,100,000 due and carries 600,000; October pays 2,000,000 of 1,650,000 —
     * the 350,000 over is not carried, November starts clean.
     */
    @Test
    void whatIsLeftUnpaidIsCarriedMonthByMonth() {
        invest(SEP, "1500000");
        invest(OCT, "2000000");

        assertThat(carried(AUG, "INVESTMENTS")).isEqualByComparingTo("0");      // the tracking start
        assertThat(carried(SEP, "INVESTMENTS")).isEqualByComparingTo("1050000");
        assertThat(carried(OCT, "INVESTMENTS")).isEqualByComparingTo("600000");
        assertThat(carried(NOV, "INVESTMENTS")).isEqualByComparingTo("0");
    }

    /**
     * The owner's example: more than the target in one month (theirs: 2.5M against 2M) does not lower
     * the next month's — it still asks its whole target.
     */
    @Test
    void anOverpaymentNeverCarries() {
        invest(AUG, "1500000");                                             // 1,050,000 asked

        assertThat(carried(SEP, "INVESTMENTS")).isEqualByComparingTo("0");
        AllocationLedgerResponse sep = service.getAllocationLedger(SEP, Currency.UZS);
        AllocationLedgerResponse.BucketLedger investments = sep.getBuckets().get(2);
        assertThat(investments.getCarried()).isEqualByComparingTo("0");
        assertThat(investments.getOutstanding()).isEqualByComparingTo("1050000");   // not 600,000
    }

    /** Nothing paid into the donation bucket: each month's 700,000 piles up; the ledger says the same. */
    @Test
    void anUnpaidDonationIsCarriedAndTheLedgerAgrees() {
        assertThat(carried(OCT, "DONATION")).isEqualByComparingTo("1400000");

        AllocationLedgerResponse oct = service.getAllocationLedger(OCT, Currency.UZS);
        AllocationLedgerResponse.BucketLedger donation = oct.getBuckets().getFirst();
        assertThat(donation.getCarried()).isEqualByComparingTo("1400000");
        assertThat(donation.getOutstanding()).isEqualByComparingTo("2100000");   // 700,000 + 1,400,000
        assertThat(oct.getCarriedStartMonth()).isEqualTo("2026-08");
        assertThat(oct.getCarriedEndMonth()).isEqualTo("2026-09");
    }

    /**
     * October takes a bank loan and a debt: its rule is 5 / 0 / 5 %, so it asks nothing of the
     * emergency fund — but August's and September's 350,000 each are still owed, and carried on.
     */
    @Test
    void aBucketTheRuleDoesNotAskForStillCarries() {
        BankLoan bank = new BankLoan();
        bank.setId(1L);
        bank.setMonthlyPayment(new BigDecimal("400000"));
        bank.setTotalAmount(new BigDecimal("4800000"));
        bank.setCurrency(Currency.UZS);
        bank.setTakenDate(OCT.atDay(1));
        banks.add(bank);
        Debt shop = new Debt();
        shop.setId(2L);
        shop.setCreditorName("Shop");
        shop.setTotalAmount(new BigDecimal("300000"));
        shop.setPaidAmount(BigDecimal.ZERO);
        shop.setCurrency(Currency.UZS);
        shop.setStatus(RecordStatus.PENDING);
        shop.setBorrowedDate(OCT.atDay(2));
        debts.add(shop);

        assertThat(carried(OCT, "EMERGENCY")).isEqualByComparingTo("700000");
        assertThat(carried(NOV, "EMERGENCY")).isEqualByComparingTo("700000");   // October asked 0 more
        assertThat(service.getAllocationLedger(OCT, Currency.UZS).getBuckets().get(1).getOutstanding())
                .isEqualByComparingTo("700000");
    }
}
