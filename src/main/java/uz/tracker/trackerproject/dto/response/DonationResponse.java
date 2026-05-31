package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.Donation;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Builder
public class DonationResponse {

    private Long id;
    private String recipientName;     // real value, used by edit form
    private String displayName;       // "Anonymous" when anonymous, otherwise recipientName
    private BigDecimal amount;
    private Currency currency;
    private LocalDate donationDate;
    private String description;
    private Boolean anonymous;
    private LocalDateTime createdAt;

    public static DonationResponse from(Donation d) {
        boolean anon = Boolean.TRUE.equals(d.getAnonymous());
        return DonationResponse.builder()
                .id(d.getId())
                .recipientName(d.getRecipientName())
                .displayName(anon ? "Anonymous" : d.getRecipientName())
                .amount(d.getAmount())
                .currency(d.getCurrency())
                .donationDate(d.getDonationDate())
                .description(d.getDescription())
                .anonymous(d.getAnonymous())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
