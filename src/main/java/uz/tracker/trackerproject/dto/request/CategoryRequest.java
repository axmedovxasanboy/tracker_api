package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.CategoryKind;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.TransactionSubType;

@Getter @Setter
public class CategoryRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotNull(message = "Type is required")
    private CategoryType type;

    private String color;

    private String icon;

    private TransactionSubType applicableSubType;

    private Long parentId;

    /** Optional — null means inherit from parent on the server (or GENERIC for root). */
    private CategoryKind kind;

    /** Custom label for the description field on transactions in this category. */
    private String descriptionLabel;

    /** When false, description becomes optional. Null treated as true. */
    private Boolean descriptionRequired;

    /** When true (donation sub-category), selecting it triggers an anonymous donation flow. */
    private Boolean anonymizes;

    /** When true (income category), its income tops up the month's allocation target (tier % of the bonus). */
    private Boolean bonusIncome;
}
