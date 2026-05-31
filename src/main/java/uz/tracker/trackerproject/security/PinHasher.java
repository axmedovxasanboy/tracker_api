package uz.tracker.trackerproject.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PinHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public String hash(String rawPin) {
        return encoder.encode(rawPin);
    }

    public boolean matches(String rawPin, String storedHash) {
        if (rawPin == null || storedHash == null || storedHash.isBlank()) return false;
        return encoder.matches(rawPin, storedHash);
    }

    public boolean looksHashed(String value) {
        // BCrypt hashes start with $2a$, $2b$, or $2y$ and are 60 chars long.
        return value != null && value.length() == 60 && value.startsWith("$2");
    }
}
