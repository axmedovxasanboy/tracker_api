package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.tracker.trackerproject.entity.MarkPaid;

import java.time.LocalDate;
import java.util.List;

public interface MarkPaidRepository extends JpaRepository<MarkPaid, Long> {

    /** All "already paid" marks for a given month (first-of-month). */
    List<MarkPaid> findByMonth(LocalDate month);

    /** Marks for one target (subscription / loan / debt) within a month. */
    List<MarkPaid> findByKindAndRefIdAndMonth(String kind, Long refId, LocalDate month);
}
