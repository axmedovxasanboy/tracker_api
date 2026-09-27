package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.CashBalanceRequest;
import uz.tracker.trackerproject.dto.request.CurrentCashRequest;
import uz.tracker.trackerproject.dto.response.CashBalanceResponse;
import uz.tracker.trackerproject.dto.response.CurrentCashResponse;
import uz.tracker.trackerproject.entity.CashBalance;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.CashBalanceRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CashBalanceService {

    private final CashBalanceRepository repo;
    private final MonthCloseService monthCloseService;
    private final SettingsService settingsService;

    /**
     * "Update cash" — the cash the owner holds NOW (the web and the bot both say so), not the cash
     * they started with. The difference from what the app computes is booked like a wallet
     * check-in of the cash pot alone: money gone is an everyday-spending EXPENSE, money found a
     * surplus INCOME, dated {@code date}. The starting cash is left as it is, and so is every other
     * wallet (this is not a check-in: the cards' check-in timer does not restart).
     */
    @Transactional
    public CurrentCashResponse setCurrent(CurrentCashRequest req) {
        settingsService.assertStableIncomeSet();
        LocalDate date = req.getDate() != null ? req.getDate() : LocalDate.now();
        if (date.isAfter(LocalDate.now().plusDays(1))) {   // a day of slack for Tashkent ahead of UTC
            throw new IllegalArgumentException("Cash can't be updated for a day that hasn't come yet.");
        }
        monthCloseService.assertMonthOpen(date);
        Currency currency = req.getCurrency();
        String key = MonthCloseService.walletKey("CASH", null, currency);
        BigDecimal computed = monthCloseService.computedWallets(date, Set.of(key)).stream()
                .filter(w -> "CASH".equals(w.type()) && w.currency() == currency)
                .map(MonthCloseService.ComputedWallet::computed)
                .findFirst().orElse(BigDecimal.ZERO);
        // The pot shows in the wallet lists only once it has a row: one starting at 0, never rewritten.
        if (repo.findByCurrency(currency).isEmpty()) {
            CashBalance fresh = new CashBalance();
            fresh.setCurrency(currency);
            fresh.setInitialBalance(BigDecimal.ZERO);
            repo.save(fresh);
        }
        BigDecimal delta = computed.subtract(req.getAmount());          // + gone, − found
        Long txId = monthCloseService.bookAdjustment(null, currency, delta, date,
                MonthCloseService.Reconciliation.CASH_UPDATE);
        return CurrentCashResponse.builder().currency(currency).date(date)
                .previousBalance(computed).balance(req.getAmount()).adjustment(delta.negate())
                .adjustmentTxId(txId).build();
    }

    @Transactional(readOnly = true)
    public List<CashBalanceResponse> getAll() {
        return repo.findAll().stream()
                .map(c -> CashBalanceResponse.from(c, repo.sumCashlessTransactions(c.getCurrency())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CashBalanceResponse getByCurrency(Currency currency) {
        CashBalance c = repo.findByCurrency(currency)
                .orElseThrow(() -> new ResourceNotFoundException("CashBalance for " + currency));
        return CashBalanceResponse.from(c, repo.sumCashlessTransactions(currency));
    }

    /**
     * Upsert by currency — one cash balance per currency, ever. This sets the STARTING cash; the cash
     * held now is {@link #setCurrent}.
     */
    @Transactional
    public CashBalanceResponse upsert(CashBalanceRequest req) {
        CashBalance c = repo.findByCurrency(req.getCurrency())
                .orElseGet(() -> {
                    CashBalance fresh = new CashBalance();
                    fresh.setCurrency(req.getCurrency());
                    return fresh;
                });
        c.setInitialBalance(req.getInitialBalance() != null ? req.getInitialBalance() : BigDecimal.ZERO);
        CashBalance saved = repo.save(c);
        return CashBalanceResponse.from(saved, repo.sumCashlessTransactions(saved.getCurrency()));
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw new ResourceNotFoundException("CashBalance", id);
        repo.deleteById(id);
    }
}
