package uz.tracker.trackerproject.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * The owner's split of the month's Investments money (Batch 81): one weight per account — the
 * amounts typed in the split sheet. Share = weight ÷ their total; an account left out gets 0.
 */
@Getter @Setter @NoArgsConstructor
public class InvestmentSplitRequest {

    private List<Weight> weights;

    @Getter @Setter @NoArgsConstructor
    public static class Weight {
        private Long investmentId;
        private BigDecimal weight;
    }
}
