package com.billing.service;

import static com.billing.validation.TransactionValidator.amount;

import com.billing.model.FeeLine;
import com.billing.model.TaxLine;
import com.billing.model.TransactionRecord;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Exact decimal totals and separate tax and fee grouping for frozen snapshots. */
@Component
public class InvoiceRollup {
  private record TaxKey(String name, BigDecimal rate) {}

  /** Sums gross totals exactly once and rejects amounts beyond the storage precision. */
  public BigDecimal total(List<TransactionRecord> transactions) {
    return amount(
        transactions.stream()
            .map(TransactionRecord::total)
            .reduce(BigDecimal.ZERO, BigDecimal::add),
        "invoice total");
  }

  /** Groups taxes by exact name and numerical rate, keeping absent rates separate. */
  public List<TaxLine> taxes(List<TransactionRecord> transactions) {
    Map<TaxKey, BigDecimal> sums = new LinkedHashMap<>();
    transactions.forEach(
        t ->
            t.taxLines()
                .forEach(
                    tax -> {
                      BigDecimal rate = tax.rate() == null ? null : tax.rate().stripTrailingZeros();
                      sums.merge(new TaxKey(tax.name(), rate), tax.amount(), BigDecimal::add);
                    }));
    return sums.entrySet().stream()
        .map(e -> new TaxLine(e.getKey().name(), e.getKey().rate(), e.getValue()))
        .toList();
  }

  /** Groups merchant and carrier charges by their exact names. */
  public List<FeeLine> fees(List<TransactionRecord> transactions) {
    Map<String, BigDecimal> sums = new LinkedHashMap<>();
    transactions.forEach(
        t -> t.feeLines().forEach(fee -> sums.merge(fee.name(), fee.amount(), BigDecimal::add)));
    return sums.entrySet().stream().map(e -> new FeeLine(e.getKey(), e.getValue())).toList();
  }
}
