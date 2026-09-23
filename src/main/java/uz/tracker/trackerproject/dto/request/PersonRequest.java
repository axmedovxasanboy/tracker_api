package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.CounterpartyKind;

/** A person on the owner's lenders or borrowers list: create ({@code name}, {@code kind}) or rename ({@code name}). */
@Getter @Setter
public class PersonRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 255)
    private String name;

    /** LENDER or BORROWER — required to create; a rename keeps the person's list. */
    private CounterpartyKind kind;
}
