package uz.tracker.trackerproject.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.config.DataSeeder;
import uz.tracker.trackerproject.dto.request.InvestmentWithdrawRequest;
import uz.tracker.trackerproject.dto.request.TransactionRequest;
import uz.tracker.trackerproject.dto.response.InvestmentResponse;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Taking money out of an investment — "take 1M from IMAN": the wallet gets it, the holding goes
 * down by it, and the money is never counted as income. Plus the growth figures a holding shows.
 */
class InvestmentWithdrawalTest {

    private static final LocalDate SEP_20 = LocalDate.of(2026, 9, 20);

    private final List<Transaction> transactions = new ArrayList<>();
    private InvestmentRepository investmentRepository;
    private TransactionRepository transactionRepository;
    private SettingsService settingsService;
    private MonthCloseService monthCloseService;
    private FinanceService service;
    private Investment iman;
    private Card uzcard;
    private Category takenFromSavings;
    private long nextId = 700;

    @BeforeEach
    void setUp() {
        investmentRepository = mock(InvestmentRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        CardRepository cardRepository = mock(CardRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        settingsService = mock(SettingsService.class);
        monthCloseService = mock(MonthCloseService.class);

        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(nextId++);
                transactions.add(t);
            }
            return t;
        });
        when(investmentRepository.save(any(Investment.class))).thenAnswer(inv -> inv.getArgument(0));

        iman = new Investment();
        iman.setId(9L);
        iman.setName("IMAN");
        iman.setType(InvestmentType.OTHER);
        iman.setCurrency(Currency.UZS);
        iman.setInvestedAmount(new BigDecimal("5000000"));
        iman.setCurrentValue(new BigDecimal("5600000"));
        iman.setPurchaseDate(LocalDate.of(2026, 6, 1));
        when(investmentRepository.findById(9L)).thenReturn(Optional.of(iman));

        uzcard = new Card();
        uzcard.setId(3L);
        uzcard.setCurrency(Currency.UZS);
        when(cardRepository.findById(3L)).thenReturn(Optional.of(uzcard));
        takenFromSavings = new Category();
        takenFromSavings.setId(31L);
        takenFromSavings.setName("Taken from savings");
        when(categoryRepository.findByApplicableSubTypeAndParentIsNull(TransactionSubType.INVESTMENT_WITHDRAWAL))
                .thenReturn(List.of(takenFromSavings));

        service = new FinanceService(mock(DebtRepository.class), mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class), mock(BankLoanRepository.class), mock(MonthlyPaymentRepository.class),
                mock(DonationRepository.class), investmentRepository, categoryRepository, transactionRepository,
                cardRepository, mock(MarkPaidRepository.class), mock(CardService.class), monthCloseService,
                settingsService, mock(CounterpartyService.class));
    }

    private static InvestmentWithdrawRequest take(String amount, Long cardId) {
        InvestmentWithdrawRequest r = new InvestmentWithdrawRequest();
        r.setAmount(new BigDecimal(amount));
        r.setCurrency(Currency.UZS);
        r.setDate(SEP_20);
        r.setCardId(cardId);
        return r;
    }

    /** 1M from IMAN onto the card: an INCOME the card adds up, linked to IMAN, in no bucket; IMAN down by 1M. */
    @Test
    void takingMoneyOutOntoACardBooksItAndLowersTheHolding() {
        InvestmentResponse r = service.withdrawFromInvestment(9L, take("1000000", 3L));

        assertThat(transactions).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(TransactionType.INCOME);
            assertThat(t.getSubType()).isEqualTo(TransactionSubType.INVESTMENT_WITHDRAWAL);
            assertThat(t.getInvestmentId()).isEqualTo(9L);
            assertThat(t.getCard()).isSameAs(uzcard);
            assertThat(t.getCashAmount()).isEqualByComparingTo("0");
            assertThat(t.getAllocationBucket()).isNull();
            assertThat(t.getCategory()).isSameAs(takenFromSavings);
            assertThat(t.getDescription()).isEqualTo("Taken from IMAN");
            assertThat(t.getTransactionDate()).isEqualTo(SEP_20);
        });
        assertThat(r.getPutIn()).isEqualByComparingTo("4000000");
        assertThat(r.getValue()).isEqualByComparingTo("4600000");
        verify(settingsService).assertStableIncomeSet();
        verify(monthCloseService).assertMonthOpen(SEP_20);
    }

    /** Into cash; more than was put in (it grew) empties what was put in, the value keeps the rest. */
    @Test
    void takingMoreThanWasPutInIntoCash() {
        service.withdrawFromInvestment(9L, take("5300000", null));

        assertThat(transactions.getFirst().getCard()).isNull();
        assertThat(transactions.getFirst().getCashAmount()).isEqualByComparingTo("5300000");
        assertThat(iman.getInvestedAmount()).isEqualByComparingTo("0");
        assertThat(iman.getCurrentValue()).isEqualByComparingTo("300000");
    }

    /** Never more than it is worth, and only in its own currency. */
    @Test
    void moreThanTheValueOrAnotherCurrencyIsRefused() {
        assertThatThrownBy(() -> service.withdrawFromInvestment(9L, take("5600001", null)))
                .isInstanceOf(IllegalArgumentException.class);
        InvestmentWithdrawRequest dollars = take("100", null);
        dollars.setCurrency(Currency.USD);
        assertThatThrownBy(() -> service.withdrawFromInvestment(9L, dollars)).isInstanceOf(IllegalArgumentException.class);
        assertThat(transactions).isEmpty();
        assertThat(iman.getInvestedAmount()).isEqualByComparingTo("5000000");

        iman.setCurrentValue(null);                                  // untracked: the value is what was put in
        service.withdrawFromInvestment(9L, take("5000000", null));
        assertThat(iman.getInvestedAmount()).isEqualByComparingTo("0");
        assertThat(iman.getCurrentValue()).isNull();
    }

    /** Putting a withdrawal back — its transaction deleted — raises both again. */
    @Test
    void reversingAWithdrawalPutsTheMoneyBack() {
        service.withdrawFromInvestment(9L, take("1000000", null));
        service.reverseWithdrawal(9L, new BigDecimal("1000000"));

        assertThat(iman.getInvestedAmount()).isEqualByComparingTo("5000000");
        assertThat(iman.getCurrentValue()).isEqualByComparingTo("5600000");
    }

    /** From the Transactions page: deleting the withdrawal reverses it; editing it applies the difference. */
    @Test
    void theTransactionsPageReversesAndReAppliesIt() {
        TransactionRepository txRepo = mock(TransactionRepository.class);
        FinanceService finance = mock(FinanceService.class);
        TransactionService tx = new TransactionService(txRepo, mock(CategoryRepository.class), mock(CardRepository.class),
                mock(CashBalanceRepository.class), finance, mock(MonthCloseService.class), mock(SettingsService.class),
                mock(LoanGivenRepository.class), mock(LoanTakenRepository.class), mock(DonationRepository.class),
                mock(InvestmentRepository.class));
        Transaction row = new Transaction();
        row.setId(40L);
        row.setType(TransactionType.INCOME);
        row.setSubType(TransactionSubType.INVESTMENT_WITHDRAWAL);
        row.setInvestmentId(9L);
        row.setAmount(new BigDecimal("1000000"));
        row.setCurrency(Currency.UZS);
        row.setTransactionDate(SEP_20);
        when(txRepo.findById(40L)).thenReturn(Optional.of(row));
        when(txRepo.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionRequest edit = new TransactionRequest();
        edit.setType(TransactionType.INCOME);
        edit.setSubType(TransactionSubType.INVESTMENT_WITHDRAWAL);
        edit.setInvestmentId(9L);
        edit.setAmount(new BigDecimal("1500000"));
        edit.setCurrency(Currency.UZS);
        edit.setTransactionDate(SEP_20);
        tx.update(40L, edit);
        verify(finance).applyWithdrawal(9L, new BigDecimal("500000"));

        row.setAmount(new BigDecimal("1500000"));
        tx.delete(40L);
        verify(finance).reverseWithdrawal(9L, new BigDecimal("1500000"));
        verify(finance, never()).removeFundsFromInvestment(any(), any());
    }

    /** What was put in, what it is worth, how much it grew and by what percent. */
    @Test
    void aHoldingShowsItsGrowth() {
        InvestmentResponse r = InvestmentResponse.from(iman);
        assertThat(r.getPutIn()).isEqualByComparingTo("5000000");
        assertThat(r.getValue()).isEqualByComparingTo("5600000");
        assertThat(r.getGrowth()).isEqualByComparingTo("600000");
        assertThat(r.getGrowthPercent()).isEqualByComparingTo("12.0");

        iman.setCurrentValue(null);
        assertThat(InvestmentResponse.from(iman).getGrowth()).isEqualByComparingTo("0");
        iman.setInvestedAmount(BigDecimal.ZERO);
        iman.setCurrentValue(new BigDecimal("300000"));
        assertThat(InvestmentResponse.from(iman).getGrowthPercent()).isNull();
    }

    /** The database's sub-type CHECKs — transactions and categories — are rebuilt with the new value. */
    @Test
    void theSubTypeCheckConstraintsAllowTheWithdrawal() throws Exception {
        DataSeeder seeder = new DataSeeder(mock(CategoryRepository.class), mock(CardRepository.class),
                mock(CashBalanceRepository.class), mock(TransactionRepository.class), mock(LoanTakenRepository.class),
                mock(DebtRepository.class), mock(CounterpartyService.class));
        EntityManager em = mock(EntityManager.class);
        List<String> sql = new ArrayList<>();
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            sql.add(inv.getArgument(0));
            return mock(Query.class);
        });
        Field field = DataSeeder.class.getDeclaredField("entityManager");
        field.setAccessible(true);
        field.set(seeder, em);
        Method rebuild = DataSeeder.class.getDeclaredMethod("rebuildSubTypeCheckConstraint");
        rebuild.setAccessible(true);
        rebuild.invoke(seeder);

        assertThat(sql).filteredOn(s -> s.contains("ADD CONSTRAINT"))
                .hasSize(2)
                .allSatisfy(s -> assertThat(s).contains("'INVESTMENT_WITHDRAWAL'"));
    }
}
