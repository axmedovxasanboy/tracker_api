package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
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

    /**
     * Uzbek display name. Nullable: when unset the English {@link #name} is shown instead.
     * NOTE: {@code name} stays the canonical English identifier — all internal matching
     * (DataSeeder's Donation/Anonymous lookups, the frontend's Donation check) keys off it,
     * so it must never be replaced with a translation.
     */
    @Column(name = "name_uz")
    private String nameUz;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CategoryType type;

    private String color;

    private String icon;

    @Enumerated(EnumType.STRING)
    @Column(name = "applicable_sub_type")
    private TransactionSubType applicableSubType;

    // A `kind` column (GENERIC / FOOD / TRANSPORT) used to follow here. Its only job was to add
    // category-specific fields to the add form — a place for FOOD, From/To for TRANSPORT — and
    // those were removed, so the concept went with them. The column is left in the database,
    // unmapped and nullable, which is also why its CHECK constraint is harmless: NULL passes it.

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
