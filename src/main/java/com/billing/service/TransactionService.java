package com.billing.service;

import com.billing.api.ApiException;
import com.billing.api.Pagination;
import com.billing.model.TransactionRecord;
import com.billing.persistence.TransactionRepository;
import com.billing.validation.TransactionValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic transaction writes, duplicate identity, and billed-source restrictions. */
@Service
public class TransactionService {
  private final TransactionRepository repository;
  private final TransactionValidator validator;
  private final TransactionTemplate writes;

  /** Creates the component with its required collaborators. */
  public TransactionService(
      TransactionRepository repository,
      TransactionValidator validator,
      PlatformTransactionManager manager) {
    this.repository = repository;
    this.validator = validator;
    this.writes = new TransactionTemplate(manager);
  }

  /** Creates one aggregate or reports the existing ID after a duplicate write rolls back. */
  public TransactionRecord create(JsonNode body) {
    String externalId = validator.externalId(body);
    repository.idByExternalId(externalId).ifPresent(this::duplicate);
    TransactionRecord value = validator.create(body, UUID.randomUUID().toString());
    try {
      return writes.execute(
          status -> {
            repository.insert(value);
            return value;
          });
    } catch (DuplicateKeyException conflict) {
      // PostgreSQL aborts a transaction after a unique violation. Lookup happens AFTER rollback.
      var existing = repository.idByExternalId(externalId);
      if (existing.isPresent()) {
        duplicate(existing.get());
      }
      throw conflict;
    }
  }

  private void duplicate(String id) {
    throw new ApiException(409, "external_id already exists", id);
  }

  /** Reads the full transaction aggregate from one consistent database version. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public TransactionRecord get(String id) {
    return repository.get(id);
  }

  /** Replaces supported fields atomically, rejecting any billed source. */
  public TransactionRecord patch(String id, JsonNode body) {
    return writes.execute(
        status -> {
          repository.lock(id);
          editable(id);
          TransactionRecord updated = validator.patch(repository.get(id), body);
          repository.update(updated);
          return updated;
        });
  }

  /** Deletes the source and its charges atomically, rejecting any billed source. */
  public void delete(String id) {
    writes.executeWithoutResult(
        status -> {
          repository.lock(id);
          editable(id);
          repository.delete(id);
        });
  }

  private void editable(String id) {
    if (repository.invoiced(id)) {
      throw new ApiException(409, "Transaction is included on an invoice");
    }
  }

  /** Returns a bounded page of transaction summaries. */
  public List<Map<String, Object>> list(Pagination page) {
    return repository.list(page);
  }
}
