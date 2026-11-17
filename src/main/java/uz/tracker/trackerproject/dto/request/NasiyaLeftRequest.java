package uz.tracker.trackerproject.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** POST /finance/loans-taken/{id}/left — the amount a nasiya still owes, as Uzum shows it. */
@Getter @Setter
public class NasiyaLeftRequest {
    /** 0 or more; 0 settles the nasiya. */
    private BigDecimal left;
}
