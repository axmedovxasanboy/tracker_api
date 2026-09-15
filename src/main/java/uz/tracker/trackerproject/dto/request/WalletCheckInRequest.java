package uz.tracker.trackerproject.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * Record a wallet check-in: each wallet's real balance on {@link #date}. A wallet left out is
 * taken to match the app exactly and gets no adjustment — the same rule the month close uses.
 */
@Getter @Setter
public class WalletCheckInRequest {

    /**
     * The owner's local date, sent by the client. The server's own clock is UTC and five hours
     * behind Tashkent, so between midnight and 05:00 it would name the wrong day — and on the
     * 1st, the wrong month, which decides whether a check-in is allowed at all.
     */
    @NotNull(message = "date is required")
    private LocalDate date;

    @Valid
    private List<MonthCloseRequest.WalletBalanceEntry> wallets;
}
