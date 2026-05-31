package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Builder
public class TransactionResponse {

    private Long id;
    private TransactionType type;
    private BigDecimal amount;
    /** Portion paid in cash (0 if pure card or pure no-card). */
    private BigDecimal cashAmount;
    /** Convenience: amount - cashAmount. The portion that hit the linked card. */
    private BigDecimal cardAmount;
    private Currency currency;
    private CategoryResponse category;
    private CardSummaryResponse card;
    private String description;
    private LocalDate transactionDate;
    private LocalDateTime createdAt;
    private String note;
    private TransactionSubType subType;
    private Long investmentId;
    private String place;
    private String fromLocation;
    private String toLocation;
    private Long transferPairId;
    private Long repaidLoanTakenId;
    private Long repaidLoanGivenId;
    private Long repaidDebtId;

    public static TransactionResponse from(Transaction t) {
        CardSummaryResponse card = null;
        if (t.getCard() != null) {
            card = CardSummaryResponse.builder()
                    .id(t.getCard().getId())
                    .name(t.getCard().getName())
                    .bankName(t.getCard().getBankName())
                    .type(t.getCard().getType())
                    .lastFourDigits(t.getCard().getLastFourDigits())
                    .currency(t.getCard().getCurrency())
                    .color(t.getCard().getColor())
                    .build();
        }
        BigDecimal cash = t.getCashAmount() != null ? t.getCashAmount() : BigDecimal.ZERO;
        BigDecimal cardPortion = t.getAmount().subtract(cash);
        if (cardPortion.signum() < 0) cardPortion = BigDecimal.ZERO;
        return TransactionResponse.builder()
                .id(t.getId())
                .type(t.getType())
                .amount(t.getAmount())
                .cashAmount(cash)
                .cardAmount(cardPortion)
                .currency(t.getCurrency())
                .category(t.getCategory() != null ? CategoryResponse.from(t.getCategory()) : null)
                .card(card)
                .description(t.getDescription())
                .transactionDate(t.getTransactionDate())
                .createdAt(t.getCreatedAt())
                .note(t.getNote())
                .subType(t.getSubType())
                .investmentId(t.getInvestmentId())
                .place(t.getPlace())
                .fromLocation(t.getFromLocation())
                .toLocation(t.getToLocation())
                .transferPairId(t.getTransferPairId())
                .repaidLoanTakenId(t.getRepaidLoanTakenId())
                .repaidLoanGivenId(t.getRepaidLoanGivenId())
                .repaidDebtId(t.getRepaidDebtId())
                .build();
    }
}
