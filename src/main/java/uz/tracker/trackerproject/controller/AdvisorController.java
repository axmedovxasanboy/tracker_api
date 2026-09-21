package uz.tracker.trackerproject.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.tracker.trackerproject.dto.response.AdvisorResponse;
import uz.tracker.trackerproject.service.AdvisorService;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/v1/advisor")
@RequiredArgsConstructor
public class AdvisorController {

    private final AdvisorService service;

    /**
     * The advisor for {@code date} (the owner's local day, YYYY-MM-DD; today when omitted): what
     * they have, what is coming, what this month still asks for, what is free, and what to do next.
     */
    @GetMapping
    public ResponseEntity<AdvisorResponse> advise(@RequestParam(required = false) String date) {
        return ResponseEntity.ok(service.advise(parseDate(date)));
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
