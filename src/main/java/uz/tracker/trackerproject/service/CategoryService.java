package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.CategoryRequest;
import uz.tracker.trackerproject.dto.response.CategoryResponse;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.enums.CategoryKind;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> getAll(CategoryType type, TransactionSubType subType) {
        List<Category> roots;
        if (type == null) {
            roots = categoryRepository.findByParentIsNull();
        } else if (type == CategoryType.INCOME) {
            roots = categoryRepository.findByParentIsNullAndTypeIn(List.of(CategoryType.INCOME));
        } else if (type == CategoryType.EXPENSE) {
            roots = categoryRepository.findByParentIsNullAndTypeIn(List.of(CategoryType.EXPENSE));
        } else {
            roots = categoryRepository.findByParentIsNull().stream()
                    .filter(c -> c.getType() == type)
                    .toList();
        }
        if (subType != null) {
            roots = roots.stream()
                    .filter(c -> c.getApplicableSubType() == null || c.getApplicableSubType() == subType)
                    .toList();
        }
        return roots.stream().map(CategoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> getSubCategories(Long parentId) {
        return categoryRepository.findByParentId(parentId)
                .stream().map(CategoryResponse::flat).toList();
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        boolean clash = request.getParentId() == null
                ? categoryRepository.existsByNameAndParentIsNull(request.getName())
                : categoryRepository.existsByNameAndParentId(request.getName(), request.getParentId());
        if (clash) {
            throw new IllegalArgumentException("Category '" + request.getName() + "' already exists in this scope");
        }
        Category category = new Category();
        applyRequest(category, request);
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
        boolean clash = request.getParentId() == null
                ? categoryRepository.existsByNameAndParentIsNullAndIdNot(request.getName(), id)
                : categoryRepository.existsByNameAndParentIdAndIdNot(request.getName(), request.getParentId(), id);
        if (clash) {
            throw new IllegalArgumentException("Category '" + request.getName() + "' already exists in this scope");
        }
        applyRequest(category, request);
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public void delete(Long id) {
        if (!categoryRepository.existsById(id)) throw new ResourceNotFoundException("Category", id);
        // Detach: children become roots, transactions lose the category link.
        categoryRepository.detachChildren(id);
        transactionRepository.detachFromCategory(id);
        categoryRepository.deleteById(id);
    }

    private void applyRequest(Category c, CategoryRequest req) {
        c.setName(req.getName());
        c.setType(req.getType());
        c.setColor(req.getColor() != null ? req.getColor() : "#6366f1");
        if (req.getIcon() != null) c.setIcon(req.getIcon());
        c.setApplicableSubType(req.getApplicableSubType());
        Category parent = null;
        if (req.getParentId() != null) {
            parent = categoryRepository.findById(req.getParentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category", req.getParentId()));
        }
        c.setParent(parent);

        // Sub-categories inherit the parent's kind by default. The client may override.
        CategoryKind kind = req.getKind();
        if (kind == null) {
            kind = parent != null && parent.getKind() != null ? parent.getKind() : CategoryKind.GENERIC;
        }
        c.setKind(kind);

        // descriptionLabel + descriptionRequired: explicit request value wins; otherwise
        // inherit from parent if present; otherwise leave null (default "Description", required).
        if (req.getDescriptionLabel() != null) {
            c.setDescriptionLabel(req.getDescriptionLabel().isBlank() ? null : req.getDescriptionLabel().trim());
        } else if (parent != null) {
            c.setDescriptionLabel(parent.getDescriptionLabel());
        }
        if (req.getDescriptionRequired() != null) {
            c.setDescriptionRequired(req.getDescriptionRequired());
        } else if (parent != null && parent.getDescriptionRequired() != null) {
            c.setDescriptionRequired(parent.getDescriptionRequired());
        }
        c.setAnonymizes(Boolean.TRUE.equals(req.getAnonymizes()));
        c.setBonusIncome(Boolean.TRUE.equals(req.getBonusIncome()));
    }
}
