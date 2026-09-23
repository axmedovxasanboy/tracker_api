package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.CounterpartyKind;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One person on the lenders or borrowers list, with what the owner's records with them add up to.
 * UZS records only, like every other figure.
 */
@Getter @Builder
public class PersonResponse {

    private Long id;
    private String name;
    private CounterpartyKind kind;
    /** How many records: borrowed money and debts for a lender, money lent for a borrower. */
    private int times;
    /** Σ of their total amounts. */
    private BigDecimal total;
    /** Σ of what is still owed on the records not yet settled. */
    private BigDecimal open;
    /** The latest borrowed / lent date; null with no records. */
    private LocalDate lastDate;
}
