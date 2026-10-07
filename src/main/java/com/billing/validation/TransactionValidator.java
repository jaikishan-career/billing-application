package com.billing.validation;

import com.billing.api.ApiException;
import com.billing.model.FeeLine;
import com.billing.model.TaxLine;
import com.billing.model.TransactionRecord;
import com.billing.validation.MetadataModels.Coupon;
import com.billing.validation.MetadataModels.Extra;
import com.billing.validation.MetadataModels.Flight;
import com.billing.validation.MetadataModels.Hotel;
import com.billing.validation.MetadataModels.Leg;
import com.billing.validation.MetadataModels.NavanFee;
import com.billing.validation.MetadataModels.Rail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates shared transaction fields, decimal money, and typed metadata. */
@Component
public class TransactionValidator {
  private final ObjectMapper mapper;
  private static final Set<String> FIELDS =
      Set.of(
          "type",
          "external_id",
          "currency",
          "occurred_at",
          "total",
          "tax_lines",
          "fee_lines",
          "metadata",
          "comment");
  private static final Set<String> PATCH_FIELDS =
      Set.of("total", "tax_lines", "fee_lines", "metadata", "occurred_at");

  /** Creates the component with its required collaborators. */
  public TransactionValidator(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** Validates the external identity before checking duplicate precedence. */
  public String externalId(JsonNode body) {
    object(body, "body");
    return text(body, "external_id", 200);
  }

  /** Validates a complete transaction request and preserves its metadata structure. */
  public TransactionRecord create(JsonNode body, String id) {
    object(body, "body");
    body.fieldNames()
        .forEachRemaining(field -> require(FIELDS.contains(field), "Unknown field: " + field));
    String type = text(body, "type", 20);
    require(
        Set.of("flight", "hotel", "rail", "navan_fee").contains(type), "Unknown transaction type");
    String currency = text(body, "currency", 3);
    require(currency.matches("[A-Z]{3}"), "currency must be three uppercase letters");
    OffsetDateTime occurred;
    try {
      occurred = OffsetDateTime.parse(text(body, "occurred_at", 80));
    } catch (Exception e) {
      throw bad("occurred_at must be an ISO-8601 timestamp with an offset");
    }
    BigDecimal total = money(body.get("total"), "total");
    List<TaxLine> taxes = taxes(body.get("tax_lines"));
    List<FeeLine> fees = fees(body.get("fee_lines"));
    BigDecimal extras =
        taxes.stream()
            .map(TaxLine::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .add(fees.stream().map(FeeLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    require(extras.compareTo(total) <= 0, "tax and fee amounts cannot exceed gross total");
    metadata(type, body.get("metadata"));
    return new TransactionRecord(
        id,
        type,
        externalId(body),
        currency,
        occurred,
        total,
        taxes,
        fees,
        body.get("metadata").deepCopy());
  }

  /** Merges supported replacements with stored values and validates the result. */
  public TransactionRecord patch(TransactionRecord existing, JsonNode patch) {
    object(patch, "patch");
    require(!patch.isEmpty(), "patch must contain at least one field");
    var merged = mapper.valueToTree(existing);
    var object = (com.fasterxml.jackson.databind.node.ObjectNode) merged;
    object.remove("id");
    patch
        .fields()
        .forEachRemaining(
            entry -> {
              require(
                  PATCH_FIELDS.contains(entry.getKey()),
                  "Field cannot be patched: " + entry.getKey());
              object.set(entry.getKey(), entry.getValue());
            });
    return create(object, existing.id());
  }

  private List<TaxLine> taxes(JsonNode node) {
    array(node, "tax_lines", false);
    List<TaxLine> result = new ArrayList<>();
    for (JsonNode line : node) {
      object(line, "tax line");
      BigDecimal rate = null;
      if (line.hasNonNull("rate")) {
        require(line.get("rate").isNumber(), "rate must be numeric");
        rate = line.get("rate").decimalValue();
        require(
            rate.signum() >= 0
                && rate.compareTo(BigDecimal.ONE) <= 0
                && rate.stripTrailingZeros().scale() <= 6,
            "rate must be between 0 and 1 with at most 6 decimal places");
      }
      result.add(
          new TaxLine(text(line, "name", 200), rate, money(line.get("amount"), "tax amount")));
    }
    return List.copyOf(result);
  }

  private List<FeeLine> fees(JsonNode node) {
    array(node, "fee_lines", false);
    List<FeeLine> result = new ArrayList<>();
    for (JsonNode line : node) {
      object(line, "fee line");
      result.add(new FeeLine(text(line, "name", 200), money(line.get("amount"), "fee amount")));
    }
    return List.copyOf(result);
  }

  private void metadata(String type, JsonNode node) {
    object(node, "metadata");
    try {
      switch (type) {
        case "flight" -> {
          Flight m = mapper.treeToValue(node, Flight.class);
          nonblank(m.origin(), "origin");
          nonblank(m.destination(), "destination");
          amount(m.fare(), "fare");
          require(
              m.coupons() != null && !m.coupons().isEmpty(), "flight requires at least one coupon");
          for (Coupon c : m.coupons()) {
            require(c != null, "coupon cannot be null");
            nonblank(c.flightNumber(), "flight_number");
            nonblank(c.from(), "from");
            nonblank(c.to(), "to");
            times(c.departure(), c.arrival());
          }
        }
        case "hotel" -> {
          Hotel m = mapper.treeToValue(node, Hotel.class);
          nonblank(m.name(), "name");
          require(m.address() != null, "hotel address required");
          nonblank(m.address().street(), "street");
          nonblank(m.address().city(), "city");
          nonblank(m.address().country(), "country");
          require(
              m.checkIn() != null && m.checkOut() != null && m.checkOut().isAfter(m.checkIn()),
              "invalid hotel dates");
          require(
              m.nights() != null
                  && m.nights() > 0
                  && m.nights() == ChronoUnit.DAYS.between(m.checkIn(), m.checkOut()),
              "nights must match hotel dates");
          amount(m.roomRatePerNight(), "room_rate_per_night");
          if (m.extras() != null) {
            for (Extra e : m.extras()) {
              require(e != null, "extra cannot be null");
              nonblank(e.name(), "extra name");
              amount(e.amount(), "extra amount");
            }
          }
        }
        case "rail" -> {
          Rail m = mapper.treeToValue(node, Rail.class);
          require(m.legs() != null && !m.legs().isEmpty(), "rail requires at least one leg");
          for (Leg leg : m.legs()) {
            require(leg != null, "leg cannot be null");
            nonblank(leg.origin(), "origin");
            nonblank(leg.destination(), "destination");
            times(leg.departure(), leg.arrival());
          }
        }
        case "navan_fee" -> {
          NavanFee m = mapper.treeToValue(node, NavanFee.class);
          require(
              m.feeKind() != null && Set.of("trip_fee", "agent_call_fee").contains(m.feeKind()),
              "Unknown fee_kind");
        }
        default -> throw bad("Unknown type");
      }
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw bad("Invalid " + type + " metadata");
    }
  }

  /** Validates nonnegative DECIMAL(19,2) money without silently rounding fractions. */
  public static BigDecimal amount(BigDecimal value, String name) {
    require(
        value != null
            && value.signum() >= 0
            && value.stripTrailingZeros().scale() <= 2
            && value.compareTo(new BigDecimal("99999999999999999.99")) <= 0,
        name + " must be nonnegative with at most 2 decimal places and fit DECIMAL(19,2)");
    return value.setScale(2, RoundingMode.UNNECESSARY);
  }

  private BigDecimal money(JsonNode node, String name) {
    require(node != null && node.isNumber(), name + " must be numeric");
    return amount(node.decimalValue(), name);
  }

  private void times(OffsetDateTime departure, OffsetDateTime arrival) {
    require(
        departure != null && arrival != null && arrival.isAfter(departure),
        "arrival must be after departure");
  }

  private void object(JsonNode node, String name) {
    require(node != null && node.isObject(), name + " must be an object");
  }

  private void array(JsonNode node, String name, boolean nonempty) {
    require(
        node != null && node.isArray() && node.size() <= 1000 && (!nonempty || !node.isEmpty()),
        name + " must be an array of at most 1000 entries");
  }

  private String text(JsonNode node, String name, int max) {
    JsonNode value = node.get(name);
    require(
        value != null
            && value.isTextual()
            && !value.textValue().isBlank()
            && value.textValue().length() <= max,
        "Invalid " + name);
    return value.textValue();
  }

  private void nonblank(String value, String name) {
    require(value != null && !value.isBlank(), "Missing " + name);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw bad(message);
    }
  }

  private static ApiException bad(String message) {
    return new ApiException(400, message);
  }
}
