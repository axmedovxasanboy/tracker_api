package uz.tracker.trackerproject.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.tracker.trackerproject.dto.response.ProfileResponse;
import uz.tracker.trackerproject.service.ProfileService;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Authenticated like every other /api/v1 route (SecurityConfig: anyRequest().authenticated()). */
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService service;

    /**
     * The profile for {@code date}'s month (the owner's local day, YYYY-MM-DD; today when omitted):
     * the level, and how the savings percentages come from the stable income.
     */
    @GetMapping
    public ResponseEntity<ProfileResponse> profile(@RequestParam(required = false) String date,
                                                   Authentication authentication) {
        return ResponseEntity.ok(service.profile(parseDate(date),
                authentication == null ? null : authentication.getName()));
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD, got: " + date);
        }
    }
}
