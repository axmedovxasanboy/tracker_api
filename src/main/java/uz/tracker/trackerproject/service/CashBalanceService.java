package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.CashBalanceRequest;
import uz.tracker.trackerproject.dto.response.CashBalanceResponse;
import uz.tracker.trackerproject.entity.CashBalance;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.CashBalanceRepository;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CashBalanceService {

    private final CashBalanceRepository repo;

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

    /** Upsert by currency — one cash balance per currency, ever. */
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
