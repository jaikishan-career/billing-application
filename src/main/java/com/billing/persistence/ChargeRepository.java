package com.billing.persistence;

import com.billing.model.FeeLine;
import com.billing.model.TaxLine;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Table names can only come from this enum, never requests. */
@Repository
public class ChargeRepository {
  /** Trusted table and foreign-key names for source charges or snapshot charges. */
  public enum Owner {
    /** Charges attached to a current source transaction. */
    TRANSACTION("transaction_tax_lines", "transaction_fee_lines", "transaction_id"),
    /** Copied charges attached to an immutable invoice line. */
    SNAPSHOT("invoice_tax_lines", "invoice_fee_lines", "invoice_line_id");
    final String taxTable;
    final String feeTable;
    final String key;

    Owner(String taxTable, String feeTable, String key) {
      this.taxTable = taxTable;
      this.feeTable = feeTable;
      this.key = key;
    }
  }

  private final JdbcTemplate jdbc;

  /** Creates the component with its required collaborators. */
  public ChargeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Loads tax lines for a source transaction or snapshot in input order. */
  public List<TaxLine> taxes(Owner owner, String id) {
    return jdbc.query(
        "SELECT name, rate, amount FROM "
            + owner.taxTable
            + " WHERE "
            + owner.key
            + "=? ORDER BY position",
        (rs, row) ->
            new TaxLine(rs.getString("name"), rs.getBigDecimal("rate"), rs.getBigDecimal("amount")),
        id);
  }

  /** Loads fee lines for a source transaction or snapshot in input order. */
  public List<FeeLine> fees(Owner owner, String id) {
    return jdbc.query(
        "SELECT name, amount FROM "
            + owner.feeTable
            + " WHERE "
            + owner.key
            + "=? ORDER BY position",
        (rs, row) -> new FeeLine(rs.getString("name"), rs.getBigDecimal("amount")),
        id);
  }

  /** Batch-inserts ordered charges within the caller transaction. */
  public void insert(Owner owner, String id, List<TaxLine> taxes, List<FeeLine> fees) {
    List<Object[]> taxArgs = new ArrayList<>();
    for (int i = 0; i < taxes.size(); i++) {
      TaxLine t = taxes.get(i);
      taxArgs.add(new Object[] {id, i, t.name(), t.rate(), t.amount()});
    }
    if (!taxArgs.isEmpty()) {
      jdbc.batchUpdate(
          "INSERT INTO "
              + owner.taxTable
              + " ("
              + owner.key
              + ",position,name,rate,amount) VALUES (?,?,?,?,?)",
          taxArgs);
    }
    List<Object[]> feeArgs = new ArrayList<>();
    for (int i = 0; i < fees.size(); i++) {
      FeeLine f = fees.get(i);
      feeArgs.add(new Object[] {id, i, f.name(), f.amount()});
    }
    if (!feeArgs.isEmpty()) {
      jdbc.batchUpdate(
          "INSERT INTO "
              + owner.feeTable
              + " ("
              + owner.key
              + ",position,name,amount) VALUES (?,?,?,?)",
          feeArgs);
    }
  }

  /** Replaces both charge lists within the caller transaction after locking the source. */
  public void replace(String transactionId, List<TaxLine> taxes, List<FeeLine> fees) {
    jdbc.update("DELETE FROM transaction_tax_lines WHERE transaction_id=?", transactionId);
    jdbc.update("DELETE FROM transaction_fee_lines WHERE transaction_id=?", transactionId);
    insert(Owner.TRANSACTION, transactionId, taxes, fees);
  }
}
