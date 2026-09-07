package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

/** One "already paid" mark — the acknowledgement of a write and the row the user can undo. */
@Getter @Builder
public class MarkPaidResponse {

    private Long id;
    private String kind;
    private Long refId;
    private String bucket;
    private String month;
    private BigDecimal amount;
    private Currency currency;
    /** The user's own reason for the mark. Carried so a listed mark is recognisable. */
    private String note;

    public static MarkPaidResponse from(MarkPaid m) {
        return MarkPaidResponse.builder()
                .id(m.getId())
                .kind(m.getKind())
                .refId(m.getRefId())
                .bucket(m.getBucket())
                .month(m.getMonth() == null ? null : m.getMonth().toString())
                .amount(m.getAmount())
                .currency(m.getCurrency())
                .note(m.getNote())
                .build();
    }
}
