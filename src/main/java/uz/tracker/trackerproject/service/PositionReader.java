package uz.tracker.trackerproject.service;

import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Position;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.PositionLoan;
import uz.tracker.trackerproject.entity.Emergency;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.repository.EmergencyRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * What the owner owned and owed on a day — {@code GET /analytics}' {@code position}, taken out of
 * AnalyticsService so {@code SnapshotScheduler} records the very same figures (ANALYTICS-V2-SPEC §4.6)
 * once the old endpoint is no longer called. Built by hand from the beans its callers already hold.
 *
 * @param valueOf what a holding was worth that day: {@link AdvisorService#value} for today, a
 *                rebuilt value for a past month's last day
 */
record PositionReader(MonthCloseService monthCloseService, EmergencyRepository emergencyRepository,
                      OverviewService overviewService, LoanGivenRepository loanGivenRepository) {

    Position position(LocalDate date, List<Investment> holdings, Function<Investment, BigDecimal> valueOf) {
        // The advisor's `have`: every UZS wallet's computed balance at the end of the day.
        BigDecimal wallets = BigDecimal.ZERO;
        for (MonthCloseService.ComputedWallet w : monthCloseService.computedWallets(date, Set.of())) {
            if (w.currency() != null && w.currency() != Currency.UZS) continue;
            wallets = wallets.add(nz(w.computed()));
        }

        // The Savings page's split: a goal, else the emergency fund, else a plain investment.
        BigDecimal emergencyFund = BigDecimal.ZERO;
        BigDecimal investments = BigDecimal.ZERO;
        BigDecimal goals = BigDecimal.ZERO;
        for (Investment i : holdings) {
            BigDecimal value = valueOf.apply(i);
            if (Boolean.TRUE.equals(i.getSavingsGoal())) goals = goals.add(value);
            else if (Boolean.TRUE.equals(i.getEmergencyFund())) emergencyFund = emergencyFund.add(value);
            else investments = investments.add(value);
        }
        List<Emergency> contributions = emergencyRepository.findAllByOrderByDateDesc();
        for (Emergency e : contributions == null ? List.<Emergency>of() : contributions) {
            if (e.getAmount() == null || (e.getCurrency() != null && e.getCurrency() != Currency.UZS)) continue;
            if (e.getDate() != null && e.getDate().isAfter(date)) continue;
            emergencyFund = emergencyFund.add(e.getAmount());
        }
        BigDecimal own = wallets.add(emergencyFund).add(investments).add(goals);

        // The same list, and the same `left`, the advisor's `owe` adds up.
        List<PositionLoan> loans = new ArrayList<>();
        for (OverviewService.OpenLoan l : overviewService.openLoans(date)) {
            loans.add(PositionLoan.builder().kind(l.kind()).refId(l.refId()).name(l.name()).asap(l.asap())
                    .original(l.original()).left(l.left()).monthly(l.monthly())
                    .paidOffBy(l.paidOffBy() == null ? null : l.paidOffBy().toString()).build());
        }
        BigDecimal loansLeft = BigDecimal.ZERO;
        for (PositionLoan l : loans) if (l.getLeft() != null) loansLeft = loansLeft.add(l.getLeft());

        BigDecimal owed = BigDecimal.ZERO;
        List<LoanGiven> given = loanGivenRepository.findAll();
        for (LoanGiven l : given == null ? List.<LoanGiven>of() : given) owed = owed.add(AdvisorService.stillOwedToOwner(l));

        return Position.builder().asOf(date).wallets(wallets).emergencyFund(emergencyFund)
                .investments(investments).goals(goals).own(own).loans(loans).loansLeft(loansLeft)
                .owedToYou(owed).net(own.subtract(loansLeft)).build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
