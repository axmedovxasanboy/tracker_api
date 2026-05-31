package uz.tracker.trackerproject.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
public class BulkTransactionRequest {

    private Long cardId;

    @NotEmpty(message = "At least one transaction is required")
    @Valid
    private List<TransactionRequest> transactions;
}
