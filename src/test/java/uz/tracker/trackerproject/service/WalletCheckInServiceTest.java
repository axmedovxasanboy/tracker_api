package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.tracker.trackerproject.dto.request.MonthCloseRequest;
import uz.tracker.trackerproject.dto.request.WalletCheckInRequest;
import uz.tracker.trackerproject.dto.response.WalletCheckInResponse;
import uz.tracker.trackerproject.dto.response.WalletCheckInStatusResponse;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.entity.CashBalance;
import uz.tracker.trackerproject.entity.MonthClose;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.entity.WalletCheckIn;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CashBalanceRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.MonthCloseRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;
import uz.tracker.trackerproject.repository.WalletCheckInRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the wallet check-in rules — when one is allowed, when it is due — and what it books.
 * A real MonthCloseService over mocked repositories, so the reconciliation it shares with the
 * close is the code under test too. Pure Mockito: no Spring context, no database.
 */
class WalletCheckInServiceTest {

    private MonthCloseRepository monthCloseRepository;
    private CardRepository cardRepository;
    private CashBalanceRepository cashBalanceRepository;
    private TransactionRepository transactionRepository;
    private WalletCheckInRepository checkInRepository;
    private WalletCheckInService service;

    @BeforeEach
    void setUp() {
        monthCloseRepository = mock(MonthCloseRepository.class);
        cardRepository = mock(CardRepository.class);
        cashBalanceRepository = mock(CashBalanceRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        checkInRepository = mock(WalletCheckInRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        SettingsService settingsService = mock(SettingsService.class);

        when(monthCloseRepository.findTopByOrderByMonthDesc()).thenReturn(Optional.empty());
        when(checkInRepository.findTopByOrderByDateDescIdDesc()).thenReturn(Optional.empty());
        when(checkInRepository.save(any(WalletCheckIn.class))).thenAnswer(i -> i.getArgument(0));
        when(categoryRepository.findByApplicableSubTypeAndParentIsNull(any())).thenReturn(List.of());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            t.setId(700L);
            return t;
        });

        // One card holding 1 000 000 by the app's reckoning, and a cash pot holding 200 000.
        Card card = new Card();
        card.setId(1L);
        card.setName("Visa");
        card.setCurrency(Currency.UZS);
        card.setInitialBalance(new BigDecimal("1000000"));
        when(cardRepository.findAll()).thenReturn(List.of(card));
        when(cardRepository.sumTransactionsByCardIdUpTo(eq(1L), any())).thenReturn(BigDecimal.ZERO);
        CashBalance pot = new CashBalance();
        pot.setCurrency(Currency.UZS);
        pot.setInitialBalance(new BigDecimal("200000"));
        when(cashBalanceRepository.findByCurrency(Currency.UZS)).thenReturn(Optional.of(pot));
        when(cashBalanceRepository.sumCashlessTransactionsUpTo(any(), any())).thenReturn(BigDecimal.ZERO);

        MonthCloseService monthCloseService = new MonthCloseService(
                monthCloseRepository, cardRepository, cashBalanceRepository, transactionRepository,
                categoryRepository, mock(OverviewService.class), settingsService);
        service = new WalletCheckInService(monthCloseService, checkInRepository, settingsService);
    }

    private void lastClosed(String yearMonthFirstDay) {
        MonthClose m = new MonthClose();
        m.setMonth(LocalDate.parse(yearMonthFirstDay));
        when(monthCloseRepository.findTopByOrderByMonthDesc()).thenReturn(Optional.of(m));
    }

    private void lastCheckIn(String day) {
        WalletCheckIn c = new WalletCheckIn();
        c.setDate(LocalDate.parse(day));
        when(checkInRepository.findTopByOrderByDateDescIdDesc()).thenReturn(Optional.of(c));
    }

    // ── When a check-in is allowed ────────────────────────────────────────────

    @Test
    void midMonthWithNoReconciliationEverItIsAllowedAndDueToday() {
        WalletCheckInStatusResponse s = service.status(LocalDate.parse("2026-09-15"));
        assertThat(s.isAllowed()).isTrue();
        assertThat(s.isDue()).isTrue();
        assertThat(s.getNextDueOn()).isEqualTo(LocalDate.parse("2026-09-15"));
        assertThat(s.getWallets()).hasSize(2);
    }

    @Test
    void theLastDaysOfAMonthAllowACheckInToo() {
        // September has 30 days: 26 + 5 = 1 October, which used to refuse it (MONTH_ENDING). The web
        // app no longer closes months, so a check-in is its only way to reconcile until the 1st.
        WalletCheckInStatusResponse s = service.status(LocalDate.parse("2026-09-26"));
        assertThat(s.isAllowed()).isTrue();
        assertThat(s.getBlockedCode()).isNull();
        assertThat(s.getBlockedReason()).isNull();
        assertThat(s.isDue()).isTrue();   // never reconciled
        assertThat(s.getNextDueOn()).isEqualTo(LocalDate.parse("2026-09-26"));
        assertThat(s.getDaysUntilMonthEnd()).isEqualTo(4);
        assertThat(s.getNextMonthStart()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test
    void everyDayOfAnOpenMonthIsAllowedTheLastOneIncluded() {
        for (String day : List.of("2026-09-01", "2026-09-25", "2026-09-26", "2026-09-30",
                "2027-02-24", "2027-02-28")) {
            assertThat(service.status(LocalDate.parse(day)).isAllowed()).as(day).isTrue();
        }
    }

    @Test
    void aClosedMonthIsLocked() {
        lastClosed("2026-09-01");
        WalletCheckInStatusResponse s = service.status(LocalDate.parse("2026-09-10"));
        assertThat(s.isAllowed()).isFalse();
        assertThat(s.getBlockedCode()).isEqualTo("MONTH_CLOSED");
    }

    // ── When one is due ───────────────────────────────────────────────────────

    @Test
    void closingAMonthCountsAsAReconciliationForTheNextOne() {
        // The trap: asking "is 5 September in a later month?" of AUGUST says yes, and would never
        // suggest a September check-in. It has to be asked of the current month.
        lastClosed("2026-08-01");
        WalletCheckInStatusResponse s = service.status(LocalDate.parse("2026-09-03"));
        assertThat(s.isAllowed()).isTrue();
        assertThat(s.getLastReconciledOn()).isEqualTo(LocalDate.parse("2026-08-31"));
        assertThat(s.getDaysSinceLastReconciled()).isEqualTo(3);
        assertThat(s.isDue()).isFalse();
        assertThat(s.getNextDueOn()).isEqualTo(LocalDate.parse("2026-09-05"));
    }

    @Test
    void itBecomesDueFiveDaysAfterTheLastCheckIn() {
        lastCheckIn("2026-09-10");
        assertThat(service.status(LocalDate.parse("2026-09-14")).isDue()).isFalse();
        assertThat(service.status(LocalDate.parse("2026-09-15")).isDue()).isTrue();
    }

    @Test
    void itIsSuggestedNotEnforced() {
        // Two days after the last one: not due, but allowed — a mistyped balance must be fixable.
        lastCheckIn("2026-09-10");
        WalletCheckInStatusResponse s = service.status(LocalDate.parse("2026-09-12"));
        assertThat(s.isAllowed()).isTrue();
        assertThat(s.isDue()).isFalse();
        assertThat(s.getNextDueOn()).isEqualTo(LocalDate.parse("2026-09-15"));
    }

    @Test
    void anOverdueCheckInIsDueToday() {
        lastCheckIn("2026-09-01");
        WalletCheckInStatusResponse s = service.status(LocalDate.parse("2026-09-12"));
        assertThat(s.isDue()).isTrue();
        assertThat(s.getNextDueOn()).isEqualTo(LocalDate.parse("2026-09-12"));
    }

    // ── What it books ─────────────────────────────────────────────────────────

    private MonthCloseRequest.WalletBalanceEntry entry(String type, Long cardId, String balance) {
        MonthCloseRequest.WalletBalanceEntry e = new MonthCloseRequest.WalletBalanceEntry();
        e.setWalletType(type);
        e.setCardId(cardId);
        e.setCurrency(Currency.UZS);
        e.setEnteredBalance(new BigDecimal(balance));
        return e;
    }

    @Test
    void aShortfallIsEverydaySpendingAndASurplusIsTheOppositeCorrection() {
        WalletCheckInRequest req = new WalletCheckInRequest();
        req.setDate(LocalDate.parse("2026-09-10"));
        req.setWallets(List.of(
                entry("CARD", 1L, "940000"),   // 60 000 went somewhere unrecorded
                entry("CASH", null, "215000"))); // 15 000 more than the app thought

        WalletCheckInResponse r = service.checkIn(req);

        ArgumentCaptor<Transaction> booked = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(booked.capture());
        Transaction spend = booked.getAllValues().get(0);
        Transaction surplus = booked.getAllValues().get(1);

        assertThat(spend.getSubType()).isEqualTo(TransactionSubType.EVERYDAY_SPENDING);
        assertThat(spend.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(spend.getAmount()).isEqualByComparingTo("60000");
        assertThat(spend.getCard().getId()).isEqualTo(1L);
        assertThat(spend.getTransactionDate()).isEqualTo(LocalDate.parse("2026-09-10"));
        assertThat(spend.getDescription()).isEqualTo("Everyday spending (wallet check-in)");

        assertThat(surplus.getType()).isEqualTo(TransactionType.INCOME);
        assertThat(surplus.getAmount()).isEqualByComparingTo("15000");
        assertThat(surplus.getCard()).isNull();
        assertThat(surplus.getCashAmount()).isEqualByComparingTo("15000");

        assertThat(r.getEverydayRecorded()).isEqualByComparingTo("45000");
        assertThat(r.getLines()).hasSize(2);
        assertThat(r.getNextDueOn()).isEqualTo(LocalDate.parse("2026-09-15"));
    }

    @Test
    void aWalletThatMatchesOrWasSkippedBooksNothing() {
        WalletCheckInRequest req = new WalletCheckInRequest();
        req.setDate(LocalDate.parse("2026-09-10"));
        req.setWallets(List.of(entry("CARD", 1L, "1000000"))); // exact; cash not mentioned at all

        WalletCheckInResponse r = service.checkIn(req);

        verify(transactionRepository, never()).save(any(Transaction.class));
        assertThat(r.getEverydayRecorded()).isEqualByComparingTo("0");
        assertThat(r.getLines()).allSatisfy(l -> assertThat(l.getAdjustmentTxId()).isNull());
    }

    @Test
    void theNextOneIsFiveDaysOnEvenWhenThatIsNextMonth() {
        WalletCheckInRequest req = new WalletCheckInRequest();
        req.setWallets(List.of());
        req.setDate(LocalDate.parse("2026-08-21"));
        assertThat(service.checkIn(req).getNextDueOn()).isEqualTo(LocalDate.parse("2026-08-26"));

        req.setDate(LocalDate.parse("2026-08-28"));
        assertThat(service.checkIn(req).getNextDueOn()).isEqualTo(LocalDate.parse("2026-09-02"));
    }

    @Test
    void theStatusCountsFiveDaysAcrossTheMonthEnd() {
        lastCheckIn("2026-08-29");
        WalletCheckInStatusResponse s = service.status(LocalDate.parse("2026-08-31"));
        assertThat(s.isAllowed()).isTrue();
        assertThat(s.isDue()).isFalse();
        assertThat(s.getNextDueOn()).isEqualTo(LocalDate.parse("2026-09-03"));

        assertThat(service.status(LocalDate.parse("2026-09-03")).isDue()).isTrue();
    }

    @Test
    void aMonthsLastDaysAreRecordedButAClosedMonthIsRefused() {
        WalletCheckInRequest req = new WalletCheckInRequest();
        req.setDate(LocalDate.parse("2026-08-28")); // 28 + 5 = 2 September: refused before
        req.setWallets(List.of(entry("CARD", 1L, "900000")));
        assertThat(service.checkIn(req).getEverydayRecorded()).isEqualByComparingTo("100000");

        lastClosed("2026-08-01");
        assertThatThrownBy(() -> service.checkIn(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is closed");
        // Only the first check-in booked anything.
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(checkInRepository, times(1)).save(any(WalletCheckIn.class));
    }

    @Test
    void aCheckInCannotBeDatedInTheFuture() {
        WalletCheckInRequest req = new WalletCheckInRequest();
        req.setDate(LocalDate.now().plusDays(10));
        assertThatThrownBy(() -> service.checkIn(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }
}
