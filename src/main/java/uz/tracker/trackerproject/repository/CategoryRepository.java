package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.TransactionSubType;

import java.util.List;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByType(CategoryType type);

    List<Category> findByTypeIn(List<CategoryType> types);

    List<Category> findByApplicableSubType(TransactionSubType applicableSubType);

    /**
     * Root (top-level) categories declaring this applicableSubType. Used by the bucket/repayment
     * auto-pick so a child that inherits/clones the sub-type (e.g. the seeded "Anonymous" donation
     * sub-category) can't make the match ambiguous and silently leave the transaction uncategorised.
     */
    List<Category> findByApplicableSubTypeAndParentIsNull(TransactionSubType applicableSubType);

    List<Category> findByParentIsNull();

    List<Category> findByParentIsNullAndTypeIn(List<CategoryType> types);

    List<Category> findByParentId(Long parentId);

    boolean existsByNameAndParentIsNull(String name);

    boolean existsByNameAndParentIsNullAndIdNot(String name, Long id);

    boolean existsByNameAndParentId(String name, Long parentId);

    boolean existsByNameAndParentIdAndIdNot(String name, Long parentId, Long id);

    long countByParentId(Long parentId);

    @Modifying
    @Query("UPDATE Category c SET c.parent = NULL WHERE c.parent.id = :parentId")
    int detachChildren(@Param("parentId") Long parentId);
}
