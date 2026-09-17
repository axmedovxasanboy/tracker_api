package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.TransactionSubType;

@Getter @Setter
public class CategoryRequest {

    @NotBlank(message = "Name is required")
    private String name;

    /** Optional Uzbek name; blank clears it and display falls back to the English name. */
    private String nameUz;

    @NotNull(message = "Type is required")
    private CategoryType type;

    private String color;

    private String icon;

    private TransactionSubType applicableSubType;

    private Long parentId;

    /** Custom label for the description field on transactions in this category. */
    private String descriptionLabel;

    /** When false, description becomes optional. Null treated as true. */
    private Boolean descriptionRequired;

    /** When true (donation sub-category), selecting it triggers an anonymous donation flow. */
    private Boolean anonymizes;

    /** When true (income category), its income adds the level's share of it to that month's targets — see Category.bonusIncome. */
    private Boolean bonusIncome;
}
