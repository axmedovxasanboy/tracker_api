package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.tracker.trackerproject.dto.request.InvestmentContributeRequest;
import uz.tracker.trackerproject.dto.request.InvestmentRequest;
import uz.tracker.trackerproject.dto.request.TransactionRequest;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CashBalanceRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.MarkPaidRepository;
import uz.tracker.trackerproject.repository.MonthlyPaymentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which allocation bucket a payment funds is decided ONCE, when the payment is written, and
 * stored on the row. Every path that can produce a bucket-funding transaction has to stamp it,
 * or the row falls back to the read-time derivation the recorded value exists to replace — and
 * with it, to a split that a checkbox can still move months later.
 */
class AllocationBucketWriteTimeTest {

    private TransactionRepository transactionRepository;
    private InvestmentRepository investmentRepository;
    private TransactionService transactionService;
    private FinanceService financeService;

    private static final LocalDate AUG_12 = LocalDate.of(2026, 8, 12);

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        investmentRepository = mock(InvestmentRepository.class);
        transactionService = new TransactionService(
                transactionRepository,
                mock(CategoryRepository.class),
                mock(CardRepository.class),
                mock(CashBalanceRepository.class),
                mock(FinanceService.class),
                mock(MonthCloseService.class),
                mock(SettingsService.class),
                mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class),
                mock(DonationRepository.class),
                investmentRepository);
        financeService = new FinanceService(
                mock(DebtRepository.class),
                mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class),
                mock(BankLoanRepository.class),
                mock(MonthlyPaymentRepository.class),
                mock(DonationRepository.class),
                investmentRepository,
                mock(CategoryRepository.class),
                transactionRepository,
                mock(CardRepository.class),
                mock(MarkPaidRepository.class),
                mock(CardService.class),
                mock(MonthCloseService.class),
                mock(SettingsService.class));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(investmentRepository.save(any(Investment.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Investment holding(long id, boolean savingsGoal) {
        Investment i = new Investment();
        i.setId(id);
        i.setName(savingsGoal ? "Car fund" : "Bonds");
        i.setSavingsGoal(savingsGoal);
        i.setInvestedAmount(new BigDecimal("1000"));
        i.setCurrency(Currency.UZS);
        return i;
    }

    private TransactionRequest topUp(Long investmentId) {
        TransactionRequest req = new TransactionRequest();
        req.setType(TransactionType.EXPENSE);
        req.setSubType(TransactionSubType.INVESTMENT);
        req.setInvestmentId(investmentId);
        req.setAmount(new BigDecimal("300"));
        req.setCurrency(Currency.UZS);
        req.setTransactionDate(AUG_12);
        req.setDescription("Top-up");
        return req;
    }

    private InvestmentRequest newHolding(boolean savingsGoal) {
        InvestmentRequest req = new InvestmentRequest();
        req.setName(savingsGoal ? "Car fund" : "Bonds");
        req.setType(InvestmentType.OTHER);
        req.setInvestedAmount(new BigDecimal("400"));
        req.setCurrency(Currency.UZS);
        req.setPurchaseDate(AUG_12);
        req.setSavingsGoal(savingsGoal);
        return req;
    }

    private InvestmentContributeRequest contribution() {
        InvestmentContributeRequest req = new InvestmentContributeRequest();
        req.setAmount(new BigDecimal("250"));
        req.setCurrency(Currency.UZS);
        req.setDate(AUG_12);
        return req;
    }

    private Transaction savedTransaction() {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void aTopUpFromTheTransactionsPageIsStampedWithTheHoldingsBucket() {
        when(investmentRepository.findById(8L)).thenReturn(Optional.of(holding(8L, true)));

        transactionService.create(topUp(8L));

        assertThat(savedTransaction().getAllocationBucket()).isEqualTo("SAVINGS");
    }

    @Test
    void aTopUpOfAPlainHoldingIsStampedInvestments() {
        when(investmentRepository.findById(7L)).thenReturn(Optional.of(holding(7L, false)));

        transactionService.create(topUp(7L));

        assertThat(savedTransaction().getAllocationBucket()).isEqualTo("INVESTMENTS");
    }

    /**
     * A row that CREATES a holding carries no investmentId — the holding is built from the row
     * afterwards, and one built that way is never a goal. Stamping it Investments is what stops
     * the derivation being consulted for it later, once the user has had a chance to tick the box.
     */
    @Test
    void aRowThatCreatesItsOwnHoldingIsStampedInvestments() {
        transactionService.create(topUp(null));

        assertThat(savedTransaction().getAllocationBucket()).isEqualTo("INVESTMENTS");
    }

    @Test
    void aGoalCreatedFromTheInvestmentsTabStampsItsFundingRowSavings() {
        financeService.createInvestment(newHolding(true));

        assertThat(savedTransaction().getAllocationBucket()).isEqualTo("SAVINGS");
    }

    @Test
    void aPlainHoldingCreatedFromTheInvestmentsTabStampsItsFundingRowInvestments() {
        financeService.createInvestment(newHolding(false));

        assertThat(savedTransaction().getAllocationBucket()).isEqualTo("INVESTMENTS");
    }

    @Test
    void contributingToAGoalStampsTheContributionSavings() {
        when(investmentRepository.findById(8L)).thenReturn(Optional.of(holding(8L, true)));

        financeService.contributeToInvestment(8L, contribution());

        assertThat(savedTransaction().getAllocationBucket()).isEqualTo("SAVINGS");
    }

    @Test
    void contributingToAPlainHoldingStampsTheContributionInvestments() {
        when(investmentRepository.findById(7L)).thenReturn(Optional.of(holding(7L, false)));

        financeService.contributeToInvestment(7L, contribution());

        assertThat(savedTransaction().getAllocationBucket()).isEqualTo("INVESTMENTS");
    }

    /**
     * An emergency-fund holding books EMERGENCY_CONTRIBUTION, so its rows belong to the Emergency
     * bucket whatever the savings-goal flag says — and unticking "emergency fund" later must not
     * pull that money into Investments.
     */
    @Test
    void anEmergencyFundContributionIsStampedEmergency() {
        Investment fund = holding(9L, false);
        fund.setEmergencyFund(true);
        when(investmentRepository.findById(9L)).thenReturn(Optional.of(fund));

        financeService.contributeToInvestment(9L, contribution());

        assertThat(savedTransaction().getAllocationBucket()).isEqualTo("EMERGENCY");
    }
}
