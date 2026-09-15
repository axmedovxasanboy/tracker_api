package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.WalletCheckInRequest;
import uz.tracker.trackerproject.dto.response.WalletCheckInResponse;
import uz.tracker.trackerproject.dto.response.WalletCheckInStatusResponse;
import uz.tracker.trackerproject.entity.WalletCheckIn;
import uz.tracker.trackerproject.entity.WalletCheckInLine;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.repository.WalletCheckInRepository;
import uz.tracker.trackerproject.service.MonthCloseService.ComputedWallet;
import uz.tracker.trackerproject.service.MonthCloseService.Reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wallet check-ins: the month close's per-wallet reconciliation, every few days instead of once
 * a month.
 *
 * <p>Reconciling only at the close leaves a month of small unrecorded spending — a bus fare, a
 * bread, a tip — to surface as one large gap nobody can explain any more. A check-in books the
 * gap while it is still a few days' worth, as the same EVERYDAY_SPENDING adjustment the close
 * books, so the balances the app shows stay close to what is really in the wallet. The month
 * stays open and nothing is frozen: the close later reconciles whatever is left since the last
 * check-in, and its own arithmetic (everydaySpend = totalSpent − taggedRecorded) is built from
 * real balances, so it comes out the same however many check-ins came before it.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li>A check-in is suggested every {@link #INTERVAL_DAYS} days after the last reconciliation —
 *       a check-in, or the end of the last closed month. It is suggested, not enforced: an owner
 *       who just paid a lot in cash, or mistyped a balance yesterday, should not be locked out of
 *       making the numbers right.</li>
 *   <li>It is NOT offered once the next one would fall in next month. By then the close is at most
 *       {@link #INTERVAL_DAYS} days away, and the close reconciles the same wallets.</li>
 *   <li>It is not offered in a closed month, which is locked.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class WalletCheckInService {

    /**
     * Often enough that a gap is a handful of things the owner can still remember; rarely enough
     * that counting cash does not become a chore.
     */
    public static final int INTERVAL_DAYS = 5;

    private final MonthCloseService monthCloseService;
    private final WalletCheckInRepository checkInRepository;
    private final SettingsService settingsService;

    @Transactional(readOnly = true)
    public WalletCheckInStatusResponse status(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("date is required (YYYY-MM-DD)");
        Rules r = rules(date);
        YearMonth month = YearMonth.from(date);
        return WalletCheckInStatusResponse.builder()
                .date(date)
                .month(month.toString())
                .allowed(r.allowed())
                .blockedCode(r.blockedCode())
                .blockedReason(r.blockedReason())
                .daysUntilMonthEnd(r.daysUntilMonthEnd())
                .nextMonthStart(month.plusMonths(1).atDay(1))
                .lastReconciledOn(r.lastReconciledOn())
                .daysSinceLastReconciled(r.daysSince())
                .intervalDays(INTERVAL_DAYS)
                .due(r.due())
                .nextDueOn(r.nextDueOn())
                .everydaySoFar(monthCloseService.everydayRecorded(month.atDay(1), month.atEndOfMonth()))
                .checkInsThisMonth(checkInRepository.countByDateBetween(month.atDay(1), month.atEndOfMonth()))
                .wallets(monthCloseService.computedWallets(date, Set.of()).stream()
                        .map(ComputedWallet::toLine).toList())
                .build();
    }

    @Transactional
    public WalletCheckInResponse checkIn(WalletCheckInRequest req) {
        settingsService.assertStableIncomeSet();
        LocalDate date = req.getDate();
        if (date == null) throw new IllegalArgumentException("date is required (YYYY-MM-DD)");
        // The client sends the owner's local date. One day of slack covers the five hours
        // Tashkent is ahead of this server's UTC clock; anything further is simply in the future,
        // and a balance cannot be true on a day that has not happened.
        if (date.isAfter(LocalDate.now().plusDays(1))) {
            throw new IllegalArgumentException("A wallet check-in can't be dated in the future.");
        }
        Rules r = rules(date);
        if (!r.allowed()) throw new IllegalArgumentException(r.blockedReason());
        monthCloseService.assertMonthOpen(date);

        Map<String, BigDecimal> entered = new HashMap<>();
        if (req.getWallets() != null) {
            for (var e : req.getWallets()) {
                entered.put(MonthCloseService.walletKey(e.getWalletType(), e.getCardId(), e.getCurrency()),
                        e.getEnteredBalance());
            }
        }

        WalletCheckIn checkIn = new WalletCheckIn();
        checkIn.setDate(date);
        List<WalletCheckInResponse.Line> lines = new ArrayList<>();
        BigDecimal recorded = BigDecimal.ZERO;
        for (ComputedWallet w : monthCloseService.computedWallets(date, entered.keySet())) {
            // A wallet the owner skipped is taken to match — the same rule the close applies.
            BigDecimal enteredBal = entered.getOrDefault(w.key(), w.computed());
            BigDecimal delta = w.computed().subtract(enteredBal);
            Long txId = monthCloseService.bookAdjustment(w.card(), w.currency(), delta, date, Reconciliation.CHECK_IN);

            WalletCheckInLine line = new WalletCheckInLine();
            line.setWalletType(w.type());
            line.setCardId(w.cardId());
            line.setCurrency(w.currency());
            line.setComputedBalance(w.computed());
            line.setEnteredBalance(enteredBal);
            line.setEverydaySpend(delta);
            line.setAdjustmentTxId(txId);
            checkIn.addLine(line);

            lines.add(WalletCheckInResponse.Line.builder()
                    .walletType(w.type()).cardId(w.cardId()).label(w.label()).currency(w.currency())
                    .computedBalance(w.computed()).enteredBalance(enteredBal)
                    .everydaySpend(delta).adjustmentTxId(txId).build());
            if (w.currency() == Currency.UZS) recorded = recorded.add(delta);
        }
        WalletCheckIn saved = checkInRepository.save(checkIn);

        YearMonth month = YearMonth.from(date);
        return WalletCheckInResponse.builder()
                .id(saved.getId())
                .date(date)
                .lines(lines)
                .everydayRecorded(recorded)
                .everydaySoFar(monthCloseService.everydayRecorded(month.atDay(1), month.atEndOfMonth()))
                .nextDueOn(nextDueAfter(date))
                .build();
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    private record Rules(boolean allowed, String blockedCode, String blockedReason, int daysUntilMonthEnd,
                         LocalDate lastReconciledOn, Integer daysSince, boolean due, LocalDate nextDueOn) {}

    private Rules rules(LocalDate date) {
        YearMonth month = YearMonth.from(date);
        int daysLeft = (int) ChronoUnit.DAYS.between(date, month.atEndOfMonth());

        String code = null;
        String reason = null;
        if (monthCloseService.isClosed(month)) {
            code = "MONTH_CLOSED";
            reason = "The month " + month + " is closed — its wallets were reconciled at the close.";
        } else if (!openWindow(date)) {
            code = "MONTH_ENDING";
            reason = (daysLeft == 0 ? "This month ends today" : "This month ends in " + daysLeft
                    + (daysLeft == 1 ? " day" : " days"))
                    + " — your wallets will be reconciled when you close it, from "
                    + month.plusMonths(1).atDay(1) + ".";
        }
        boolean allowed = code == null;

        LocalDate last = lastReconciledOn();
        Integer daysSince = last == null ? null
                : (int) Math.max(0, ChronoUnit.DAYS.between(last, date));
        boolean due = allowed && (daysSince == null || daysSince >= INTERVAL_DAYS);
        LocalDate nextDue = null;
        if (allowed) {
            // Measured against THIS month, not the month of the last reconciliation: closing
            // August on the 31st makes a check-in due on 5 September, and "is that next month?"
            // asked of August would wrongly say yes and never suggest one.
            LocalDate candidate = last == null ? date : last.plusDays(INTERVAL_DAYS);
            if (candidate.isBefore(date)) candidate = date; // overdue: it is due today
            nextDue = !YearMonth.from(candidate).isAfter(month) && openWindow(candidate) ? candidate : null;
        }
        return new Rules(allowed, code, reason, daysLeft, last, daysSince, due, nextDue);
    }

    /**
     * Whether {@code day} is inside the window a check-in is offered in: the next one, five days
     * later, would still fall in the same month. Outside it the month close is at most five days
     * away and reconciles the same wallets, so a check-in would only be redone days later.
     */
    static boolean openWindow(LocalDate day) {
        return !YearMonth.from(day.plusDays(INTERVAL_DAYS)).isAfter(YearMonth.from(day));
    }

    /**
     * The day a check-in is next suggested after one on {@code day} — or null when the close comes
     * first. A suggested day has to be one a check-in can actually happen on: on the 24th of a
     * 31-day month the 29th is five days on, but nothing can be recorded on the 29th, so the next
     * reconciliation is the close.
     */
    private static LocalDate nextDueAfter(LocalDate day) {
        LocalDate next = day.plusDays(INTERVAL_DAYS);
        return openWindow(next) && !YearMonth.from(next).isAfter(YearMonth.from(day)) ? next : null;
    }

    /** The later of the last check-in and the last day of the last closed month: both reconcile. */
    private LocalDate lastReconciledOn() {
        LocalDate checkIn = checkInRepository.findTopByOrderByDateDescIdDesc()
                .map(WalletCheckIn::getDate).orElse(null);
        YearMonth closed = monthCloseService.latestClosedMonth();
        LocalDate close = closed == null ? null : closed.atEndOfMonth();
        if (checkIn == null) return close;
        if (close == null) return checkIn;
        return checkIn.isAfter(close) ? checkIn : close;
    }
}
