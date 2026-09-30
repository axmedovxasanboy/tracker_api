package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.entity.PositionSnapshot;
import uz.tracker.trackerproject.repository.PositionSnapshotRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * The monthly record of the owner's position (see {@link PositionSnapshot}). Written from Analytics
 * only — a read that happens to note down what it saw — in a transaction of its own, so the
 * read-only request around it is not turned into a write, and a failure here (two first requests
 * of a month racing for the one row) costs the record of that moment, never the page.
 */
@Service
@RequiredArgsConstructor
public class PositionSnapshotService {

    private final PositionSnapshotRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(YearMonth month, LocalDate asOf, BigDecimal wallets, BigDecimal emergencyFund,
                       BigDecimal investments, BigDecimal goals, BigDecimal loansLeft, BigDecimal owedToYou) {
        PositionSnapshot row = repository.findByMonth(month.atDay(1)).orElseGet(PositionSnapshot::new);
        // An older day must not overwrite a later picture of the same month.
        if (row.getAsOf() != null && asOf.isBefore(row.getAsOf())) return;
        row.setMonth(month.atDay(1));
        row.setAsOf(asOf);
        row.setWallets(wallets);
        row.setEmergencyFund(emergencyFund);
        row.setInvestments(investments);
        row.setGoals(goals);
        row.setLoansLeft(loansLeft);
        row.setOwedToYou(owedToYou);
        repository.save(row);
    }

    @Transactional(readOnly = true)
    public List<PositionSnapshot> history() {
        return repository.findAllByOrderByMonthAsc();
    }
}
