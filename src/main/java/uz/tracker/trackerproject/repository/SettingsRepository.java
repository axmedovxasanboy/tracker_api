package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.Settings;

@Repository
public interface SettingsRepository extends JpaRepository<Settings, Long> {
}
