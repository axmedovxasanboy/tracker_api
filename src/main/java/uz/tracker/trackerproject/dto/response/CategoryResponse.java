package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.enums.CategoryKind;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.TransactionSubType;

import java.util.List;

@Getter @Builder
public class CategoryResponse {

    private Long id;
    private String name;
    private CategoryType type;
    private String color;
    private String icon;
    private TransactionSubType applicableSubType;
    private CategoryKind kind;
    private String descriptionLabel;
    private Boolean descriptionRequired;
    private Boolean anonymizes;
    private Boolean bonusIncome;
    private Long parentId;
    private List<CategoryResponse> children;

    public static CategoryResponse from(Category c) {
        return CategoryResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .type(c.getType())
                .color(c.getColor())
                .icon(c.getIcon())
                .applicableSubType(c.getApplicableSubType())
                .kind(c.getKind() != null ? c.getKind() : CategoryKind.GENERIC)
                .descriptionLabel(c.getDescriptionLabel())
                .descriptionRequired(c.getDescriptionRequired() == null || c.getDescriptionRequired())
                .anonymizes(Boolean.TRUE.equals(c.getAnonymizes()))
                .bonusIncome(Boolean.TRUE.equals(c.getBonusIncome()))
                .parentId(c.getParent() != null ? c.getParent().getId() : null)
                .children(c.getChildren() == null ? List.of()
                        : c.getChildren().stream().map(CategoryResponse::flat).toList())
                .build();
    }

    // shallow mapping — no nested children to avoid infinite recursion
    public static CategoryResponse flat(Category c) {
        return CategoryResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .type(c.getType())
                .color(c.getColor())
                .icon(c.getIcon())
                .applicableSubType(c.getApplicableSubType())
                .kind(c.getKind() != null ? c.getKind() : CategoryKind.GENERIC)
                .descriptionLabel(c.getDescriptionLabel())
                .descriptionRequired(c.getDescriptionRequired() == null || c.getDescriptionRequired())
                .anonymizes(Boolean.TRUE.equals(c.getAnonymizes()))
                .bonusIncome(Boolean.TRUE.equals(c.getBonusIncome()))
                .parentId(c.getParent() != null ? c.getParent().getId() : null)
                .children(List.of())
                .build();
    }
}
