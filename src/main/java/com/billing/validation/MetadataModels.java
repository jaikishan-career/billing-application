package com.billing.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Typed metadata models validate structure while the original JSON preserves extra fields. */
public final class MetadataModels {
  private MetadataModels() {}

  /** Flight origin, destination, fare, and ticket coupons. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Flight(String origin, String destination, BigDecimal fare, List<Coupon> coupons) {}

  /** One flown segment with a flight number and offset-aware departure and arrival times. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Coupon(
      String flightNumber,
      String carrier,
      String from,
      String to,
      OffsetDateTime departure,
      OffsetDateTime arrival) {}

  /** Hotel property, address, stay dates, room rate, and optional extras. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Hotel(
      String name,
      Address address,
      LocalDate checkIn,
      LocalDate checkOut,
      Integer nights,
      BigDecimal roomRatePerNight,
      List<Extra> extras) {}

  /** Hotel property address with street, city, and country. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Address(String street, String city, String country) {}

  /** Named hotel extra included in the stay price. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Extra(String name, BigDecimal amount) {}

  /** Rail journey containing one or more ordered legs. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Rail(List<Leg> legs) {}

  /** Rail segment with stations, travel times, and optional train and class details. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Leg(
      String origin,
      String destination,
      OffsetDateTime departure,
      OffsetDateTime arrival,
      String trainNumber,
      @JsonProperty("class") String travelClass) {}

  /** Navan-owned trip or agent-call charge, optionally linked to a booking. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record NavanFee(String feeKind, String description, String relatedExternalId) {}
}
