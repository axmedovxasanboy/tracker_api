package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.InvestmentTransfer;

import java.util.List;

@Repository
public interface InvestmentTransferRepository extends JpaRepository<InvestmentTransfer, Long> {

    List<InvestmentTransfer> findAllByOrderByTransferDateDescIdDesc();

    @Query("""
            SELECT t FROM InvestmentTransfer t
            WHERE t.fromInvestmentId = :id OR t.toInvestmentId = :id
            ORDER BY t.transferDate DESC, t.id DESC
            """)
    List<InvestmentTransfer> findByInvestment(@Param("id") Long id);

    /** Rows of [investmentId, latest move date] for every holding that has a move (either side). */
    @Query("""
            SELECT t.fromInvestmentId, MAX(t.transferDate) FROM InvestmentTransfer t
            WHERE t.fromInvestmentId IS NOT NULL GROUP BY t.fromInvestmentId
            """)
    List<Object[]> latestMoveFrom();

    @Query("""
            SELECT t.toInvestmentId, MAX(t.transferDate) FROM InvestmentTransfer t
            WHERE t.toInvestmentId IS NOT NULL GROUP BY t.toInvestmentId
            """)
    List<Object[]> latestMoveTo();

    @Modifying
    @Query("UPDATE InvestmentTransfer t SET t.fromInvestmentId = NULL WHERE t.fromInvestmentId = :id")
    int detachFrom(@Param("id") Long id);

    @Modifying
    @Query("UPDATE InvestmentTransfer t SET t.toInvestmentId = NULL WHERE t.toInvestmentId = :id")
    int detachTo(@Param("id") Long id);
}
