package com.billing.service;

import com.billing.api.ApiException;
import com.billing.api.Pagination;
import com.billing.model.Invoice;
import com.billing.model.TransactionRecord;
import com.billing.persistence.InvoiceRepository;
import com.billing.persistence.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Atomic invoice lifecycle operations coordinated with source transaction locks. */
@Service
public class InvoiceService {
  private final InvoiceRepository invoices;
  private final TransactionRepository transactions;
  private final InvoiceRollup rollup;

  /** Creates the component with its required collaborators. */
  public InvoiceService(
      InvoiceRepository invoices, TransactionRepository transactions, InvoiceRollup rollup) {
    this.invoices = invoices;
    this.transactions = transactions;
    this.rollup = rollup;
  }

  /** Locks requested sources and persists a consistent, immutable invoice snapshot. */
  @Transactional
  public Invoice create(JsonNode body) {
    List<String> ids = ids(body);
    lockAll(ids);
    List<TransactionRecord> values = load(ids);
    String id = UUID.randomUUID().toString();
    invoices.insert(
        new InvoiceRepository.Header(
            id, currency(values), rollup.total(values), OffsetDateTime.now(ZoneOffset.UTC)),
        values);
    return read(id);
  }

  /** Locks the invoice and affected sources before atomically replacing snapshots. */
  @Transactional
  public Invoice patch(String id, JsonNode body) {
    InvoiceRepository.Header existing = invoices.header(id, true);
    List<String> ids = ids(body);
    Set<String> affected = new TreeSet<>(invoices.transactionIds(id));
    affected.addAll(ids);
    lockAll(affected);
    List<TransactionRecord> values = load(ids);
    invoices.replace(
        new InvoiceRepository.Header(
            id, currency(values), rollup.total(values), existing.createdAt()),
        values);
    return read(id);
  }

  /** Deletes snapshots while coordinating with concurrent source writes. */
  @Transactional
  public void delete(String id) {
    invoices.header(id, true);
    lockAll(invoices.transactionIds(id));
    invoices.delete(id);
  }

  /** Reads the header and all frozen lines from one consistent database version. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Invoice get(String id) {
    return read(id);
  }

  private Invoice read(String id) {
    InvoiceRepository.Header h = invoices.header(id, false);
    List<TransactionRecord> snapshots = invoices.snapshots(id);
    return new Invoice(
        h.id(),
        h.currency(),
        h.total(),
        h.createdAt(),
        snapshots,
        rollup.taxes(snapshots),
        rollup.fees(snapshots));
  }

  private void lockAll(Collection<String> ids) {
    // Same ordering across all invoices prevents cycles when trips overlap.
    new TreeSet<>(ids).forEach(transactions::lock);
  }

  private List<TransactionRecord> load(List<String> ids) {
    return ids.stream().map(transactions::get).toList();
  }

  private String currency(List<TransactionRecord> values) {
    String currency = values.getFirst().currency();
    if (values.stream().anyMatch(t -> !currency.equals(t.currency()))) {
      throw new ApiException(409, "Mixed currencies are not allowed");
    }
    return currency;
  }

  private List<String> ids(JsonNode body) {
    if (body == null || !body.isObject() || body.size() != 1) {
      throw new ApiException(400, "Supply transaction_id or transaction_ids");
    }
    List<String> result = new ArrayList<>();
    if (body.has("transaction_id")) {
      result.add(id(body.get("transaction_id")));
    } else {
      JsonNode values = body.get("transaction_ids");
      if (values == null || !values.isArray() || values.isEmpty() || values.size() > 100) {
        throw new ApiException(400, "transaction_ids must contain 1..100 IDs");
      }
      values.forEach(value -> result.add(id(value)));
    }
    if (new HashSet<>(result).size() != result.size()) {
      throw new ApiException(409, "Repeated transaction ID");
    }
    return List.copyOf(result);
  }

  private String id(JsonNode value) {
    if (value == null
        || !value.isTextual()
        || value.textValue().isBlank()
        || value.textValue().length() > 40) {
      throw new ApiException(400, "Invalid transaction ID");
    }
    return value.textValue();
  }

  /** Returns a bounded page of invoice summaries. */
  public List<Map<String, Object>> list(Pagination page) {
    return invoices.list(page);
  }
}
