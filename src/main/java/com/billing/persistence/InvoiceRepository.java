package com.billing.persistence;

import com.billing.api.ApiException;
import com.billing.api.Pagination;
import com.billing.model.TransactionRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence for invoice headers and independently stored snapshots. */
@Repository
public class InvoiceRepository {
  /** Invoice currency, gross total, and creation time retained across snapshot replacement. */
  public record Header(String id, String currency, BigDecimal total, OffsetDateTime createdAt) {}

  private final JdbcTemplate jdbc;
  private final ChargeRepository charges;
  private final JsonStorage json;

  /** Creates the component with its required collaborators. */
  public InvoiceRepository(JdbcTemplate jdbc, ChargeRepository charges, JsonStorage json) {
    this.jdbc = jdbc;
    this.charges = charges;
    this.json = json;
  }

  /** Loads the invoice header, optionally locking it within the caller transaction. */
  public Header header(String id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM invoices WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, n) ->
                new Header(
                    rs.getString("id"),
                    rs.getString("currency"),
                    rs.getBigDecimal("total"),
                    rs.getObject("created_at", OffsetDateTime.class)),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "Invoice not found: " + id));
  }

  /** Returns source transaction IDs in the invoice line order. */
  public List<String> transactionIds(String invoiceId) {
    return jdbc.query(
        "SELECT transaction_id FROM invoice_lines WHERE invoice_id=? ORDER BY position",
        (rs, n) -> rs.getString(1),
        invoiceId);
  }

  /** Loads copied snapshot fields and charges without reading current source values. */
  public List<TransactionRecord> snapshots(String invoiceId) {
    return jdbc.query(
        "SELECT * FROM invoice_lines WHERE invoice_id=? ORDER BY position",
        (rs, n) -> {
          String lineId = rs.getString("id");
          return new TransactionRecord(
              rs.getString("transaction_id"),
              rs.getString("type"),
              rs.getString("external_id"),
              rs.getString("currency"),
              rs.getObject("occurred_at", OffsetDateTime.class),
              rs.getBigDecimal("total"),
              charges.taxes(ChargeRepository.Owner.SNAPSHOT, lineId),
              charges.fees(ChargeRepository.Owner.SNAPSHOT, lineId),
              json.read(rs.getString("metadata")));
        },
        invoiceId);
  }

  /** Inserts a header and its snapshots within the caller transaction. */
  public void insert(Header header, List<TransactionRecord> transactions) {
    jdbc.update(
        "INSERT INTO invoices(id,currency,total,created_at) VALUES (?,?,?,?)",
        header.id(),
        header.currency(),
        header.total(),
        header.createdAt());
    insertSnapshots(header.id(), transactions);
  }

  /** Replaces a locked invoice header and all snapshot lines within one transaction. */
  public void replace(Header header, List<TransactionRecord> transactions) {
    jdbc.update(
        "UPDATE invoices SET currency=?,total=? WHERE id=?",
        header.currency(),
        header.total(),
        header.id());
    jdbc.update("DELETE FROM invoice_lines WHERE invoice_id=?", header.id());
    insertSnapshots(header.id(), transactions);
  }

  private void insertSnapshots(String invoiceId, List<TransactionRecord> transactions) {
    for (int i = 0; i < transactions.size(); i++) {
      TransactionRecord transaction = transactions.get(i);
      String lineId = UUID.randomUUID().toString();
      jdbc.update(
          "INSERT INTO invoice_lines(id, invoice_id, transaction_id, position, external_id, type,"
              + " currency, occurred_at, total, metadata) VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS"
              + " JSONB))",
          lineId,
          invoiceId,
          transaction.id(),
          i,
          transaction.externalId(),
          transaction.type(),
          transaction.currency(),
          transaction.occurredAt(),
          transaction.total(),
          transaction.metadata().toString());
      charges.insert(
          ChargeRepository.Owner.SNAPSHOT, lineId, transaction.taxLines(), transaction.feeLines());
    }
  }

  /** Deletes the locked invoice and cascades its snapshot rows, preserving sources. */
  public void delete(String id) {
    jdbc.update("DELETE FROM invoices WHERE id=?", id);
  }

  /** Returns invoice headers using SQL pagination without loading snapshot children. */
  public List<Map<String, Object>> list(Pagination page) {
    return jdbc.queryForList(
        "SELECT id,currency,total,created_at FROM invoices ORDER BY id LIMIT ? OFFSET ?",
        page.limit(),
        page.offset());
  }
}
