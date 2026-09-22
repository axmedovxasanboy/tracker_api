package uz.tracker.trackerproject.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.CardRequest;
import uz.tracker.trackerproject.dto.response.CardResponse;
import uz.tracker.trackerproject.service.CardService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService cardService;

    @GetMapping
    public ResponseEntity<List<CardResponse>> getAll() {
        return ResponseEntity.ok(cardService.getAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CardResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(cardService.getById(id));
    }

    @PostMapping
    public ResponseEntity<CardResponse> create(@Valid @RequestBody CardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cardService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CardResponse> update(@PathVariable Long id, @Valid @RequestBody CardRequest request) {
        return ResponseEntity.ok(cardService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        cardService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
