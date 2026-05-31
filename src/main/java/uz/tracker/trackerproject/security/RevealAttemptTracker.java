package uz.tracker.trackerproject.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate limiter for the /cards/{id}/reveal endpoint.
 * After N consecutive failures the card is locked for a configurable cooldown.
 */
@Component
public class RevealAttemptTracker {

    @Value("${app.card.reveal.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.card.reveal.lockout-seconds:300}")
    private long lockoutSeconds;

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    public void checkAllowed(Long cardId) {
        Entry e = entries.get(cardId);
        if (e == null) return;
        if (e.failures >= maxAttempts && Instant.now().isBefore(e.lockedUntil)) {
            long secondsLeft = e.lockedUntil.getEpochSecond() - Instant.now().getEpochSecond();
            throw new IllegalArgumentException(
                    "Too many failed attempts. Try again in " + secondsLeft + "s.");
        }
        if (e.failures >= maxAttempts && Instant.now().isAfter(e.lockedUntil)) {
            entries.remove(cardId);
        }
    }

    public void recordFailure(Long cardId) {
        entries.compute(cardId, (k, current) -> {
            Entry e = current != null ? current : new Entry();
            e.failures++;
            if (e.failures >= maxAttempts) {
                e.lockedUntil = Instant.now().plusSeconds(lockoutSeconds);
            }
            return e;
        });
    }

    public void recordSuccess(Long cardId) {
        entries.remove(cardId);
    }

    private static class Entry {
        int failures;
        Instant lockedUntil = Instant.EPOCH;
    }
}
