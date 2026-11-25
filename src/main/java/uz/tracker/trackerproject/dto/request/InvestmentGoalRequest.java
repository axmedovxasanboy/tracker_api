package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Create or edit an investment goal (Batch 79). {@code investmentIds} is the exact set of accounts
 * saving for it: on edit, accounts left out are unlinked, and an account linked to another goal moves
 * here. Null or empty = no account yet.
 */
@Getter @Setter
public class InvestmentGoalRequest {

    @NotBlank(message = "Give the goal a name.")
    private String name;

    @NotNull(message = "Enter roughly how much the goal needs.")
    @DecimalMin(value = "0.01", message = "The goal amount must be more than 0.")
    private BigDecimal targetAmount;

    private String note;

    private List<Long> investmentIds;
}
