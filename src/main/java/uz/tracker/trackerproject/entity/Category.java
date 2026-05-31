package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.CategoryKind;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.TransactionSubType;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "categories")
@Getter @Setter @NoArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CategoryType type;

    private String color;

    private String icon;

    @Enumerated(EnumType.STRING)
    @Column(name = "applicable_sub_type")
    private TransactionSubType applicableSubType;

    /**
     * Drives category-specific UI extras (e.g. FOOD shows "place", TRANSPORT shows from/to).
     * Nullable in the DB so the column add via ddl-auto=update doesn't fail on existing rows;
     * service/DTO layers treat null as GENERIC.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private CategoryKind kind;

    /**
     * Custom label for the transaction-modal "Description" field when this category is
     * selected. e.g. "Doctor name" under Healthcare, "Movie title" under Entertainment.
     * Null falls back to "Description".
     */
    @Column(name = "description_label")
    private String descriptionLabel;

    /**
     * When false, description is optional for transactions in this category and the
     * server will auto-fill it from category name + place / route if blank.
     * Null is treated as true (required).
     */
    @Column(name = "description_required")
    private Boolean descriptionRequired;

    /**
     * When true, picking this category in a Donation transaction marks the auto-created
     * Donation as anonymous and lets the client skip asking for the recipient name.
     */
    @Column(name = "anonymizes")
    private Boolean anonymizes;

    /**
     * When true, INCOME transactions in this category (or any of its children) are
     * treated as a "bonus" on top of the stable income: the Overview allocation
     * guidance applies the same tier % to the bonus amount and adds it to that month's
     * recommended allocation. Used for one-off income like holiday bonuses or a 13th
     * salary. Null/false → ordinary income, no extra allocation.
     */
    @Column(name = "bonus_income")
    private Boolean bonusIncome;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
    private List<Category> children = new ArrayList<>();
}
