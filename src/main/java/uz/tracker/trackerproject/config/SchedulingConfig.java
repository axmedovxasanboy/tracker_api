package uz.tracker.trackerproject.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling, enabled here and nowhere else. Spring Boot then runs scheduled tasks on its one-thread
 * task scheduler ({@code spring.task.scheduling.pool.size} defaults to 1), and a cron task is never
 * started again before its run has finished — so a task cannot run twice in parallel.
 * The only task: {@code LevelScheduler}.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
