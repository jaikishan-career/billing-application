package com.billing.api;

import com.billing.model.TransactionRecord;
import com.billing.service.TransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for travel transactions and bounded transaction lists. */
@RestController
@RequestMapping("/transactions")
public class TransactionController {
  private final TransactionService service;

  /** Creates the component with its required collaborators. */
  public TransactionController(TransactionService service) {
    this.service = service;
  }

  /** Creates a transaction, or returns a duplicate conflict containing the existing ID. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TransactionRecord create(@RequestBody JsonNode body) {
    return service.create(body);
  }

  /** Returns the stored transaction aggregate, or reports a missing resource. */
  @GetMapping("/{id}")
  public TransactionRecord get(@PathVariable String id) {
    return service.get(id);
  }

  /** Updates supported fields atomically when the transaction has no invoice references. */
  @PatchMapping("/{id}")
  public TransactionRecord patch(@PathVariable String id, @RequestBody JsonNode body) {
    return service.patch(id, body);
  }

  /** Deletes an uninvoiced transaction and its charges atomically. */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    service.delete(id);
  }

  /** Returns transaction summaries from a bounded SQL page. */
  @GetMapping
  public Map<String, Object> list(
      @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
    Pagination page = new Pagination(limit, offset);
    return Map.of("items", service.list(page), "limit", limit, "offset", offset);
  }
}
