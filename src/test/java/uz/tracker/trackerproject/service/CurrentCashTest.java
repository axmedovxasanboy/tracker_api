package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.CurrentCashRequest;
import uz.tracker.trackerproject.dto.response.CurrentCashResponse;
import uz.tracker.trackerproject.entity.CashBalance;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Update cash" sets the cash held NOW: 500,000 to start with and 700,000 of cash moves since make
 * 1,200,000 in the app. Less in hand is everyday spending; more is found money; the same is nothing.
 * The starting cash is never rewritten.
 */
class CurrentCashTest {

    private static final LocalDate SEP_27 = LocalDate.of(2026, 9, 27);

    private final List<Transaction> booked = new ArrayList<>();
    private CashBalanceRepository cashRepo;
    private CashBalance pot;
    private CashBalanceService service;

    @BeforeEach
    void setUp() {
        cashRepo = mock(CashBalanceRepository.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        pot = new CashBalance();
        pot.setId(1L);
        pot.setCurrency(Currency.UZS);
        pot.setInitialBalance(new BigDecimal("500000"));
        when(cashRepo.findByCurrency(Currency.UZS)).thenReturn(Optional.of(pot));
        when(cashRepo.sumCashlessTransactionsUpTo(eq(Currency.UZS), any())).thenReturn(new BigDecimal("700000"));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId(90L + booked.size());
            booked.add(t);
            return t;
        });
        MonthCloseService monthClose = new MonthCloseService(mock(MonthCloseRepository.class), mock(CardRepository.class),
                cashRepo, transactionRepository, mock(CategoryRepository.class), mock(OverviewService.class),
                mock(SettingsService.class));
        service = new CashBalanceService(cashRepo, monthClose, mock(SettingsService.class));
    }

    private CurrentCashResponse holdNow(String amount) {
        CurrentCashRequest r = new CurrentCashRequest();
        r.setCurrency(Currency.UZS);
        r.setAmount(new BigDecimal(amount));
        r.setDate(SEP_27);
        return service.setCurrent(r);
    }

    @Test
    void lessInHandIsEverydaySpending() {
        CurrentCashResponse r = holdNow("900000");

        assertThat(booked).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(TransactionType.EXPENSE);
            assertThat(t.getSubType()).isEqualTo(TransactionSubType.EVERYDAY_SPENDING);
            assertThat(t.getAmount()).isEqualByComparingTo("300000");
            assertThat(t.getCard()).isNull();
            assertThat(t.getCashAmount()).isEqualByComparingTo("300000");
            assertThat(t.getTransactionDate()).isEqualTo(SEP_27);
        });
        assertThat(r.getPreviousBalance()).isEqualByComparingTo("1200000");
        assertThat(r.getBalance()).isEqualByComparingTo("900000");
        assertThat(r.getAdjustment()).isEqualByComparingTo("-300000");
        assertThat(pot.getInitialBalance()).isEqualByComparingTo("500000");       // the starting cash stays
        verify(cashRepo, never()).save(any());
    }

    @Test
    void moreInHandIsFoundMoney() {
        CurrentCashResponse r = holdNow("1500000");

        assertThat(booked).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(TransactionType.INCOME);
            assertThat(t.getAmount()).isEqualByComparingTo("300000");
        });
        assertThat(r.getAdjustment()).isEqualByComparingTo("300000");
    }

    @Test
    void theSameAmountBooksNothing() {
        CurrentCashResponse r = holdNow("1200000");

        assertThat(booked).isEmpty();
        assertThat(r.getAdjustmentTxId()).isNull();
        assertThat(r.getAdjustment()).isEqualByComparingTo("0");
    }
}
