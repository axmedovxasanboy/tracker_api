package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One move between two holdings (Batch 78). Names are the holding's current name, else the stored one. */
@Getter @Builder
public class InvestmentTransferResponse {
    private Long id;
    /** Null once that holding was deleted. */
    private Long fromId;
    private String fromName;
    private Long toId;
    private String toName;
    private BigDecimal amount;
    private BigDecimal putInMoved;
    private Currency currency;
    private LocalDate date;
    private String note;
    private LocalDateTime createdAt;
    /** Both holdings still exist and the to-holding's value is at least the amount. */
    private boolean canUndo;
}
