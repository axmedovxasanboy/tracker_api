package uz.tracker.trackerproject.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.PersonRequest;
import uz.tracker.trackerproject.dto.response.PersonResponse;
import uz.tracker.trackerproject.enums.CounterpartyKind;
import uz.tracker.trackerproject.service.CounterpartyService;

import java.util.List;

/**
 * The owner's lenders and borrowers, each with what their records add up to — who they borrow from
 * most, and who borrows from them most. Authenticated like every other /api/v1 route.
 */
@RestController
@RequestMapping("/api/v1/people")
@RequiredArgsConstructor
public class PeopleController {

    private final CounterpartyService service;

    /** People the owner borrowed from (borrowed money and debts), the largest total first. */
    @GetMapping("/lenders")
    public ResponseEntity<List<PersonResponse>> lenders() {
        return ResponseEntity.ok(service.list(CounterpartyKind.LENDER));
    }

    /** People who borrowed from the owner (money lent), the largest total first. */
    @GetMapping("/borrowers")
    public ResponseEntity<List<PersonResponse>> borrowers() {
        return ResponseEntity.ok(service.list(CounterpartyKind.BORROWER));
    }

    /** Add a person: 201 when new, 200 with the existing one when the name is already on that list. */
    @PostMapping
    public ResponseEntity<PersonResponse> create(@Valid @RequestBody PersonRequest req) {
        CounterpartyService.Saved saved = service.create(req);
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK).body(saved.person());
    }

    /** Rename a person; their records' names follow. */
    @PutMapping("/{id}")
    public ResponseEntity<PersonResponse> rename(@PathVariable Long id, @Valid @RequestBody PersonRequest req) {
        return ResponseEntity.ok(service.rename(id, req));
    }
}
