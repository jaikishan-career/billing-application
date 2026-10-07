package com.billing.persistence;

import com.billing.api.ApiException;
import com.billing.api.Pagination;
import com.billing.model.TransactionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence and row locking for transaction aggregates. */
@Repository
public class TransactionRepository {
  private final JdbcTemplate jdbc;
  private final ChargeRepository charges;
  private final JsonStorage json;

  /** Creates the component with its required collaborators. */
  public TransactionRepository(JdbcTemplate jdbc, ChargeRepository charges, JsonStorage json) {
    this.jdbc = jdbc;
    this.charges = charges;
    this.json = json;
  }

  /** Looks up the authoritative stored ID for a client-supplied external identity. */
  public Optional<String> idByExternalId(String externalId) {
    return jdbc
        .query(
            "SELECT id FROM transactions WHERE external_id=?",
            (rs, n) -> rs.getString(1),
            externalId)
        .stream()
        .findFirst();
  }

  /** Acquires a source row lock until the caller transaction ends, or reports absence. */
  public void lock(String id) {
    if (jdbc.query(
            "SELECT id FROM transactions WHERE id=? FOR UPDATE", (rs, n) -> rs.getString(1), id)
        .isEmpty()) {
      throw new ApiException(404, "Transaction not found: " + id);
    }
  }

  /** Loads the source aggregate within the caller consistent-read transaction. */
  public TransactionRecord get(String id) {
    return jdbc.query("SELECT * FROM transactions WHERE id=?", this::map, id).stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "Transaction not found: " + id));
  }

  private TransactionRecord map(ResultSet rs, int row) throws SQLException {
    String id = rs.getString("id");
    return new TransactionRecord(
        id,
        rs.getString("type"),
        rs.getString("external_id"),
        rs.getString("currency"),
        rs.getObject("occurred_at", OffsetDateTime.class),
        rs.getBigDecimal("total"),
        charges.taxes(ChargeRepository.Owner.TRANSACTION, id),
        charges.fees(ChargeRepository.Owner.TRANSACTION, id),
        json.read(rs.getString("metadata")));
  }

  /** Inserts the aggregate atomically within the caller transaction. */
  public void insert(TransactionRecord transaction) {
    jdbc.update(
        "INSERT INTO transactions(id,external_id,type,currency,occurred_at,total,metadata) VALUES"
            + " (?,?,?,?,?,?,CAST(? AS JSONB))",
        transaction.id(),
        transaction.externalId(),
        transaction.type(),
        transaction.currency(),
        transaction.occurredAt(),
        transaction.total(),
        transaction.metadata().toString());
    charges.insert(
        ChargeRepository.Owner.TRANSACTION,
        transaction.id(),
        transaction.taxLines(),
        transaction.feeLines());
  }

  /** Replaces supported fields and charge lists after the caller locks the source row. */
  public void update(TransactionRecord transaction) {
    jdbc.update(
        "UPDATE transactions SET occurred_at=?,total=?,metadata=CAST(? AS JSONB) WHERE id=?",
        transaction.occurredAt(),
        transaction.total(),
        transaction.metadata().toString(),
        transaction.id());
    charges.replace(transaction.id(), transaction.taxLines(), transaction.feeLines());
  }

  /** Deletes the locked, uninvoiced source row and cascades its charges. */
  public void delete(String id) {
    jdbc.update("DELETE FROM transactions WHERE id=?", id);
  }

  /** Checks indexed invoice membership after the caller locks the source row. */
  public boolean invoiced(String id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM invoice_lines WHERE transaction_id=?)",
            Boolean.class,
            id));
  }

  /** Returns transaction headers using SQL pagination without loading child charges. */
  public List<Map<String, Object>> list(Pagination page) {
    return jdbc.queryForList(
        "SELECT id,external_id,type,currency,occurred_at,total FROM transactions ORDER BY id LIMIT"
            + " ? OFFSET ?",
        page.limit(),
        page.offset());
  }
}
