package com.billing.api;

import com.billing.model.Invoice;
import com.billing.service.InvoiceService;
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

/** HTTP endpoints for frozen invoice snapshots and bounded invoice lists. */
@RestController
@RequestMapping("/invoices")
public class InvoiceController {
  private final InvoiceService service;

  /** Creates the component with its required collaborators. */
  public InvoiceController(InvoiceService service) {
    this.service = service;
  }

  /** Creates a frozen invoice from one or more stored transactions. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Invoice create(@RequestBody JsonNode body) {
    return service.create(body);
  }

  /** Returns the stored snapshots and rollups for an invoice. */
  @GetMapping("/{id}")
  public Invoice get(@PathVariable String id) {
    return service.get(id);
  }

  /** Replaces all included transactions and rebuilds their snapshots atomically. */
  @PatchMapping("/{id}")
  public Invoice patch(@PathVariable String id, @RequestBody JsonNode body) {
    return service.patch(id, body);
  }

  /** Deletes an invoice and its snapshots while preserving its source transactions. */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    service.delete(id);
  }

  /** Returns invoice summaries from a bounded SQL page. */
  @GetMapping
  public Map<String, Object> list(
      @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
    Pagination page = new Pagination(limit, offset);
    return Map.of("items", service.list(page), "limit", limit, "offset", offset);
  }
}
