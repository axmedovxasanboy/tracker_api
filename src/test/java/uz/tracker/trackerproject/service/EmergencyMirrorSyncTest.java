package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.EmergencyRequest;
import uz.tracker.trackerproject.entity.Emergency;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.EmergencyRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Emergency fund is one pot recorded in two places: the money is an EMERGENCY_CONTRIBUTION
 * transaction (which is what the bucket and the wallet balance read), and the Emergencies tab
 * keeps its own row. Until the row remembered which transaction it mirrors, editing an amount
 * or deleting a row moved only one of them — leaving the bucket credited and the wallet drained
 * for a contribution the user had just removed.
 */
class EmergencyMirrorSyncTest {

    private EmergencyRepository repo;
    private TransactionRepository transactionRepository;
    private MonthCloseService monthCloseService;
    private EmergencyService service;

    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final LocalDate AUG_15 = AUG.atDay(15);

    @BeforeEach
    void setUp() {
        repo = mock(EmergencyRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        monthCloseService = mock(MonthCloseService.class);
        CardRepository cardRepository = mock(CardRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        CardService cardService = mock(CardService.class);
        SettingsService settingsService = mock(SettingsService.class);

        when(categoryRepository.findByApplicableSubTypeAndParentIsNull(any())).thenReturn(List.of());
        when(repo.save(any(Emergency.class))).thenAnswer(i -> {
            Emergency e = i.getArgument(0);
            if (e.getId() == null) e.setId(50L);
            return e;
        });
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            if (t.getId() == null) t.setId(700L);
            return t;
        });

        service = new EmergencyService(repo, transactionRepository, cardRepository,
                categoryRepository, cardService, settingsService, monthCloseService);
    }

    private EmergencyRequest request(String amount, LocalDate date, String description) {
        EmergencyRequest req = new EmergencyRequest();
        req.setAmount(new BigDecimal(amount));
        req.setCurrency(Currency.UZS);
        req.setDate(date);
        req.setDescription(description);
        return req;
    }

    /** A stored row plus the cash transaction it mirrors. */
    private Emergency existingRow(Long mirrorId, String amount) {
        Emergency e = new Emergency();
        e.setId(50L);
        e.setAmount(new BigDecimal(amount));
        e.setCurrency(Currency.UZS);
        e.setDate(AUG_15);
        e.setOriginatingTransactionId(mirrorId);
        when(repo.findById(50L)).thenReturn(Optional.of(e));
        if (mirrorId != null) {
            Transaction tx = new Transaction();
            tx.setId(mirrorId);
            tx.setType(TransactionType.EXPENSE);
            tx.setSubType(TransactionSubType.EMERGENCY_CONTRIBUTION);
            tx.setAmount(new BigDecimal(amount));
            tx.setCurrency(Currency.UZS);
            tx.setTransactionDate(AUG_15);
            tx.setCard(null);
            tx.setCashAmount(new BigDecimal(amount));
            when(transactionRepository.findById(mirrorId)).thenReturn(Optional.of(tx));
        }
        return e;
    }

    private Transaction mirrorOf(Emergency e) {
        return transactionRepository.findById(e.getOriginatingTransactionId()).orElseThrow();
    }

    @Test
    void aNewContributionRemembersTheTransactionItMirrors() {
        service.create(request("500000", AUG_15, "Rainy day"));

        org.mockito.ArgumentCaptor<Emergency> saved =
                org.mockito.ArgumentCaptor.forClass(Emergency.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getOriginatingTransactionId()).isEqualTo(700L);
    }

    /** Editing the amount here must move the money too, or only this tab shows the new figure. */
    @Test
    void editingAContributionMovesItsMirroredTransactionWithIt() {
        Emergency e = existingRow(700L, "500000");

        service.update(50L, request("800000", AUG.atDay(20), "Bigger contribution"));

        Transaction tx = mirrorOf(e);
        assertThat(tx.getAmount()).isEqualByComparingTo("800000");
        assertThat(tx.getTransactionDate()).isEqualTo(AUG.atDay(20));
        assertThat(tx.getDescription()).isEqualTo("Bigger contribution");
        assertThat(tx.getCashAmount()).isEqualByComparingTo("800000"); // cash row carries the full amount
        verify(transactionRepository).save(tx);
        // Both the month it sits in and the month it moves to must still be open.
        verify(monthCloseService).assertMonthOpen(AUG_15);
        verify(monthCloseService).assertMonthOpen(AUG.atDay(20));
    }

    /** Deleting the row alone used to leave the bucket credit — and the wallet debit — standing. */
    @Test
    void deletingAContributionTakesItsMirroredTransactionWithIt() {
        Emergency e = existingRow(700L, "500000");
        Transaction tx = mirrorOf(e);

        service.delete(50L);

        verify(transactionRepository).delete(tx);
        verify(repo).delete(e);
        verify(monthCloseService).assertMonthOpen(AUG_15);
    }

    /** Rows created before the link exists have no mirror to move — edit them, don't refuse them. */
    @Test
    void aLegacyRowWithNoMirrorIsStillEditableAndDeletable() {
        Emergency e = existingRow(null, "500000");

        service.update(50L, request("800000", AUG_15, "Corrected"));
        assertThat(e.getAmount()).isEqualByComparingTo("800000");

        service.delete(50L);
        verify(repo).delete(e);
        verify(transactionRepository, never()).delete(any(Transaction.class));
    }

    /**
     * The tab's "N contributions · total" headline can only agree with the Emergency bucket if it
     * can ask for one month. A blank month keeps the all-time list every existing caller expects.
     */
    @Test
    void theListIsMonthScopedOnlyWhenAMonthIsAskedFor() {
        Emergency august = new Emergency();
        august.setId(50L);
        august.setAmount(new BigDecimal("500000"));
        august.setCurrency(Currency.UZS);
        august.setDate(AUG_15);
        when(repo.findByDateBetweenOrderByDateDesc(AUG.atDay(1), AUG.atEndOfMonth()))
                .thenReturn(List.of(august));
        when(repo.findAllByOrderByDateDesc()).thenReturn(List.of());

        assertThat(service.getAll(AUG.toString())).hasSize(1);
        verify(repo).findByDateBetweenOrderByDateDesc(AUG.atDay(1), AUG.atEndOfMonth());

        assertThat(service.getAll(null)).isEmpty();
        assertThat(service.getAll("  ")).isEmpty();
        verify(repo, org.mockito.Mockito.times(2)).findAllByOrderByDateDesc();
    }
}
