package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.tracker.trackerproject.dto.request.TransactionRequest;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CashBalanceRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins what a transaction is called when nothing was typed into Description.
 *
 * It used to be named after its category, which made every loan given read "Loan Given": the
 * borrower's name reached the finance record and nothing else, and the category was already on
 * the row as its badge. The counterparty now comes first. Pure Mockito — no Spring context, so
 * unlike the context-load test this touches no database.
 */
class TransactionDescriptionNamingTest {

    private TransactionRepository transactionRepository;
    private CategoryRepository categoryRepository;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });
        service = new TransactionService(
                transactionRepository,
                categoryRepository,
                mock(CardRepository.class),
                mock(CashBalanceRepository.class),
                mock(FinanceService.class),
                mock(MonthCloseService.class),
                mock(SettingsService.class),
                mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class),
                mock(DonationRepository.class),
                mock(InvestmentRepository.class));
    }

    private Category category(long id, String name, Category parent) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setType(CategoryType.EXPENSE);
        c.setParent(parent);
        when(categoryRepository.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    private TransactionRequest request(TransactionSubType subType, Long categoryId,
                                       String description, String counterparty) {
        TransactionRequest r = new TransactionRequest();
        r.setType(TransactionType.EXPENSE);
        r.setAmount(new BigDecimal("500000"));
        r.setCurrency(Currency.UZS);
        r.setTransactionDate(LocalDate.of(2026, 9, 15));
        r.setSubType(subType);
        r.setCategoryId(categoryId);
        r.setDescription(description);
        r.setCounterpartyName(counterparty);
        return r;
    }

    private String savedDescription(TransactionRequest r) {
        service.create(r);
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(saved.capture());
        return saved.getValue().getDescription();
    }

    @Test
    void aLoanGivenWithNoDescriptionIsNamedAfterTheBorrower() {
        category(14, "Loan Given", null);
        assertThat(savedDescription(request(TransactionSubType.LOAN_GIVEN, 14L, null,
                "Mirjalol Sulaymonov (kursdosh)")))
                .isEqualTo("Mirjalol Sulaymonov (kursdosh)");
    }

    @Test
    void aBlankDescriptionCountsAsNoDescription() {
        category(14, "Loan Given", null);
        assertThat(savedDescription(request(TransactionSubType.LOAN_GIVEN, 14L, "   ", "Aziz")))
                .isEqualTo("Aziz");
    }

    @Test
    void whatTheUserTypedAlwaysWins() {
        category(14, "Loan Given", null);
        assertThat(savedDescription(request(TransactionSubType.LOAN_GIVEN, 14L,
                "For the laptop", "Aziz")))
                .isEqualTo("For the laptop");
    }

    @Test
    void anAnonymousDonationKeepsItsCategoryName() {
        Category donation = category(20, "Donation", null);
        Category anonymous = category(21, "Anonymous", donation);
        anonymous.setAnonymizes(true);
        assertThat(savedDescription(request(TransactionSubType.DONATION, 21L, null, "Anonymous")))
                .isEqualTo("Donation — Anonymous");
    }

    @Test
    void withNoCounterpartyTheCategoryStillNamesTheRow() {
        Category food = category(7, "Food & Dining", null);
        category(30, "Cafe", food);
        assertThat(savedDescription(request(TransactionSubType.REGULAR_EXPENSE, 30L, null, null)))
                .isEqualTo("Food & Dining — Cafe");
    }

    @Test
    void withNothingAtAllItFallsBackToTransaction() {
        assertThat(savedDescription(request(TransactionSubType.REGULAR_EXPENSE, null, null, null)))
                .isEqualTo("Transaction");
    }
}
