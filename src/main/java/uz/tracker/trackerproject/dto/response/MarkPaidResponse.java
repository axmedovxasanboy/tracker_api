package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

/** Acknowledgement of a recorded "already paid" mark. */
@Getter @Builder
public class MarkPaidResponse {

    private Long id;
    private String kind;
    private Long refId;
    private String bucket;
    private String month;
    private BigDecimal amount;
    private Currency currency;

    public static MarkPaidResponse from(MarkPaid m) {
        return MarkPaidResponse.builder()
                .id(m.getId())
                .kind(m.getKind())
                .refId(m.getRefId())
                .bucket(m.getBucket())
                .month(m.getMonth() == null ? null : m.getMonth().toString())
                .amount(m.getAmount())
                .currency(m.getCurrency())
                .build();
    }
}
