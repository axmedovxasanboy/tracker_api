package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.tracker.trackerproject.dto.request.CategoryRequest;
import uz.tracker.trackerproject.dto.response.CategoryResponse;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CashBalanceRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A category whose description is a person ("Kimga" under Family & support) offers the people
 * already named in it, the way the Loans page offers borrowers. Pins the category switch — kept by
 * an edit that leaves it out (the bot's), inherited by a new sub-category — and the names list: the
 * whole family's, without the titles the server writes for a blank description. Pure Mockito.
 */
class PersonPickerTest {

    private CategoryRepository categoryRepository;
    private TransactionRepository transactionRepository;
    private CategoryService categories;
    private TransactionService transactions;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(CategoryRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));
        when(categoryRepository.findByParentId(anyLong())).thenReturn(List.of());
        categories = new CategoryService(categoryRepository, transactionRepository);
        transactions = new TransactionService(
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

    private Category category(long id, String name, Category parent, Boolean asksPerson) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setType(CategoryType.EXPENSE);
        c.setParent(parent);
        c.setAsksPerson(asksPerson);
        when(categoryRepository.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    private CategoryRequest request(String name, Long parentId, Boolean asksPerson) {
        CategoryRequest r = new CategoryRequest();
        r.setName(name);
        r.setType(CategoryType.EXPENSE);
        r.setParentId(parentId);
        r.setAsksPerson(asksPerson);
        return r;
    }

    // ── The switch ───────────────────────────────────────────────────────────────

    @Test
    void anEditThatLeavesTheSwitchOutKeepsIt() {
        Category family = category(5, "Family & support", null, true);
        CategoryResponse saved = categories.update(5L, request("Family", null, null));
        assertThat(family.getAsksPerson()).isTrue();
        assertThat(saved.getAsksPerson()).isTrue();
    }

    @Test
    void anEditThatSendsTheSwitchSetsIt() {
        Category family = category(5, "Family & support", null, true);
        categories.update(5L, request("Family & support", null, false));
        assertThat(family.getAsksPerson()).isFalse();
    }

    @Test
    void aNewSubCategoryTakesItsParentsSwitch() {
        category(5, "Family & support", null, true);
        categories.create(request("Parents", 5L, null));
        ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(saved.capture());
        assertThat(saved.getValue().getAsksPerson()).isTrue();
    }

    @Test
    void aNewRootStartsOff() {
        CategoryResponse saved = categories.create(request("Cafe", null, null));
        assertThat(saved.getAsksPerson()).isFalse();
    }

    @Test
    void anOlderRowWithoutTheSwitchReadsOff() {
        category(9, "Transport", null, null);
        assertThat(CategoryResponse.from(categoryRepository.findById(9L).orElseThrow()).getAsksPerson()).isFalse();
    }

    // ── The names ────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void theNamesComeFromTheWholeFamily() {
        Category family = category(5, "Family & support", null, true);
        Category parents = category(6, "Parents", family, true);
        Category siblings = category(7, "Siblings", family, true);
        when(categoryRepository.findByParentId(5L)).thenReturn(List.of(parents, siblings));
        when(transactionRepository.findDescriptionsUsedIn(anyCollection())).thenReturn(List.of("Onam", "Akam"));

        // Asked from a sub-category, the list is still the family's.
        assertThat(transactions.getPersonNames(6L)).containsExactly("Onam", "Akam");
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(transactionRepository).findDescriptionsUsedIn(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(5L, 6L, 7L);
    }

    @Test
    void titlesTheServerWroteAreNotPeople() {
        Category family = category(5, "Family & support", null, true);
        family.setNameUz("Oila va yordam");
        Category parents = category(6, "Parents", family, true);
        when(categoryRepository.findByParentId(5L)).thenReturn(List.of(parents));
        when(transactionRepository.findDescriptionsUsedIn(anyCollection())).thenReturn(List.of(
                "Onam", "Family & support", "family & SUPPORT", "Oila va yordam",
                "Family & support — Parents", "—", "Singlim"));

        assertThat(transactions.getPersonNames(5L)).containsExactly("Onam", "Singlim");
    }
}
