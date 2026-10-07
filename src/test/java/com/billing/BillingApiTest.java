package com.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Uses the real HTTP server and PostgreSQL. A unique schema isolates each test run. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillingApiTest {
  private static final Properties localSettings = localSettings();
  private static final String SCHEMA =
      "billing_test_" + UUID.randomUUID().toString().replace("-", "");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    String url =
        env(
            "TEST_DB_URL",
            env(
                "DB_URL",
                localSettings.getProperty(
                    "spring.datasource.url", "jdbc:postgresql://localhost:5432/navaan")));
    registry.add(
        "spring.datasource.url",
        () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
    registry.add(
        "spring.datasource.username",
        () ->
            env(
                "TEST_DB_USER",
                env(
                    "DB_USER",
                    localSettings.getProperty("spring.datasource.username", "postgres"))));
    registry.add(
        "spring.datasource.password",
        () ->
            env(
                "TEST_DB_PASSWORD",
                env("DB_PASSWORD", localSettings.getProperty("spring.datasource.password", ""))));
    registry.add("spring.flyway.schemas", () -> SCHEMA);
    registry.add("spring.flyway.default-schema", () -> SCHEMA);
  }

  private static Properties localSettings() {
    Properties settings = new Properties();
    Path path = Path.of("application-local.properties");
    if (Files.exists(path)) {
      try (var reader = Files.newBufferedReader(path)) {
        settings.load(reader);
      } catch (Exception e) {
        throw new IllegalStateException("Cannot read local database settings", e);
      }
    }
    return settings;
  }

  private static String env(String key, String fallback) {
    return System.getenv().getOrDefault(key, fallback);
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  private JsonNode seeds;
  private JsonNode expected;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private record Reply(int status, JsonNode body) {}

  @BeforeAll
  void fixtures() throws Exception {
    seeds = mapper.readTree(Files.readString(Path.of("fixtures/task-b/seed-payloads.json")));
    expected = mapper.readTree(Files.readString(Path.of("fixtures/task-b/expected-invoice.json")));
  }

  @BeforeEach
  void reset() {
    jdbc.execute("TRUNCATE invoices,transactions CASCADE");
  }

  @AfterAll
  void cleanup() {
    jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
  }

  private Reply request(String method, String path, JsonNode body) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(30));
      builder
          .header("Content-Type", "application/json")
          .method(
              method,
              body == null
                  ? HttpRequest.BodyPublishers.noBody()
                  : HttpRequest.BodyPublishers.ofString(body.toString()));
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return new Reply(
          response.statusCode(),
          response.body().isBlank() ? mapper.nullNode() : mapper.readTree(response.body()));
    } catch (Exception e) {
      throw new AssertionError("HTTP request failed", e);
    }
  }

  private ObjectNode seed(String type) {
    return (ObjectNode) seeds.get(type).deepCopy();
  }

  private String create(String type) {
    Reply reply = request("POST", "/transactions", seed(type));
    assertThat(reply.status()).isEqualTo(201);
    return reply.body().get("id").asText();
  }

  private ObjectNode ids(String... ids) {
    ObjectNode body = mapper.createObjectNode();
    var values = body.putArray("transaction_ids");
    Arrays.stream(ids).forEach(values::add);
    return body;
  }

  private String invoice(String... ids) {
    Reply reply = request("POST", "/invoices", ids(ids));
    assertThat(reply.status()).isEqualTo(201);
    return reply.body().get("id").asText();
  }

  private void total(JsonNode body, String amount) {
    assertThat(body.get("total").decimalValue()).isEqualByComparingTo(amount);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  @Test
  void allFiveSeedTypesRoundTripAndTripInvoiceMatchesFixture() {
    List<String> ids = new ArrayList<>();
    for (String kind : List.of("flight", "hotel", "rail", "trip_fee", "agent_call_fee")) {
      String id = create(kind);
      ids.add(id);
      Reply stored = request("GET", "/transactions/" + id, null);
      assertThat(stored.status()).isEqualTo(200);
      assertThat(
              stored
                  .body()
                  .get("metadata")
                  .equals(
                      (a, b) ->
                          a.isNumber() && b.isNumber()
                              ? a.decimalValue().compareTo(b.decimalValue())
                              : (a.equals(b) ? 0 : 1),
                      seeds.get(kind).get("metadata")))
          .isTrue();
      assertThat(stored.body().get("tax_lines").size())
          .isEqualTo(seeds.get(kind).get("tax_lines").size());
      assertThat(stored.body().get("fee_lines").size())
          .isEqualTo(seeds.get(kind).get("fee_lines").size());
    }
    String id = invoice(ids.toArray(String[]::new));
    JsonNode result = request("GET", "/invoices/" + id, null).body();
    total(result, expected.get("totals").get("grand_total").asText());
    assertThat(result.get("currency").asText()).isEqualTo("EUR");
    assertThat(result.get("transactions").size()).isEqualTo(5);
    assertThat(result.at("/tax_rollup/0/name").asText()).isEqualTo("Airport tax");
    assertThat(result.at("/tax_rollup/0/amount").decimalValue()).isEqualByComparingTo("15.00");
    assertThat(result.at("/tax_rollup/1/rate").decimalValue()).isEqualByComparingTo("0.07");
    assertThat(result.at("/tax_rollup/1/amount").decimalValue()).isEqualByComparingTo("21.70");
    assertThat(result.at("/fee_rollup/0/amount").decimalValue()).isEqualByComparingTo("25.00");
    assertThat(result.at("/fee_rollup/1/amount").decimalValue()).isEqualByComparingTo("4.00");
  }

  @Test
  void duplicateFixtureReturnsExistingIdDespiteEmptyCoupons() {
    String id = create("flight");
    Reply duplicate = request("POST", "/transactions", seed("duplicate_flight"));
    assertThat(duplicate.status()).isEqualTo(409);
    assertThat(duplicate.body().get("existing_id").asText()).isEqualTo(id);
    assertThat(count("transactions")).isEqualTo(1);
    assertThat(request("POST", "/transactions", seed("flight")).status()).isEqualTo(409);
  }

  @Test
  void frozenSnapshotSurvivesDirectSourceMutation() {
    String transaction = create("flight");
    final String invoice = invoice(transaction);
    // Deliberately bypass public PATCH, which correctly refuses changes to billed transactions.
    jdbc.update(
        "UPDATE transactions SET total=999,metadata=jsonb_set(metadata,'{fare}','959') WHERE id=?",
        transaction);
    jdbc.update("UPDATE transaction_tax_lines SET amount=30 WHERE transaction_id=?", transaction);
    jdbc.update("UPDATE transaction_fee_lines SET amount=50 WHERE transaction_id=?", transaction);
    JsonNode result = request("GET", "/invoices/" + invoice, null).body();
    total(result, "220.00");
    total(result.at("/transactions/0"), "220.00");
    assertThat(result.at("/transactions/0/metadata/fare").decimalValue())
        .isEqualByComparingTo("180.00");
    assertThat(result.at("/tax_rollup/0/amount").decimalValue()).isEqualByComparingTo("15.00");
    assertThat(result.at("/fee_rollup/0/amount").decimalValue()).isEqualByComparingTo("25.00");
  }

  @Test
  void billedTransactionRejectsPatchAndDelete() {
    String transaction = create("flight");
    final String invoice = invoice(transaction);
    assertThat(
            request(
                    "PATCH",
                    "/transactions/" + transaction,
                    mapper.createObjectNode().put("total", 999))
                .status())
        .isEqualTo(409);
    assertThat(request("DELETE", "/transactions/" + transaction, null).status()).isEqualTo(409);
    total(request("GET", "/transactions/" + transaction, null).body(), "220");
    total(request("GET", "/invoices/" + invoice, null).body(), "220");
  }

  @Test
  void deletingInvoiceKeepsSourcesAndMakesThemEditable() {
    String transaction = create("flight");
    final String invoice = invoice(transaction);
    assertThat(request("DELETE", "/invoices/" + invoice, null).status()).isEqualTo(204);
    assertThat(request("GET", "/invoices/" + invoice, null).status()).isEqualTo(404);
    assertThat(request("GET", "/transactions/" + transaction, null).status()).isEqualTo(200);
    assertThat(count("invoice_lines")).isZero();
    assertThat(count("invoice_tax_lines")).isZero();
    assertThat(
            request(
                    "PATCH",
                    "/transactions/" + transaction,
                    mapper.createObjectNode().put("total", 250))
                .status())
        .isEqualTo(200);
  }

  @Test
  void anotherInvoiceStillBlocksSourceMutation() {
    String transaction = create("flight");
    String first = invoice(transaction);
    invoice(transaction);
    request("DELETE", "/invoices/" + first, null);
    assertThat(
            request(
                    "PATCH",
                    "/transactions/" + transaction,
                    mapper.createObjectNode().put("total", 250))
                .status())
        .isEqualTo(409);
  }

  @Test
  void mixedCurrencyAndMissingAndRepeatedIdsFailAtomically() {
    String flight = create("flight");
    ObjectNode usd = seed("hotel").put("currency", "USD");
    String hotel = request("POST", "/transactions", usd).body().get("id").asText();
    assertThat(request("POST", "/invoices", ids(flight, hotel)).status()).isEqualTo(409);
    assertThat(request("POST", "/invoices", ids(flight, "missing")).status()).isEqualTo(404);
    assertThat(request("POST", "/invoices", ids(flight, flight)).status()).isEqualTo(409);
    assertThat(count("invoices")).isZero();
    String invoice = invoice(flight);
    assertThat(request("PATCH", "/invoices/" + invoice, ids(flight, hotel)).status())
        .isEqualTo(409);
    assertThat(request("PATCH", "/invoices/" + invoice, ids("missing")).status()).isEqualTo(404);
    total(request("GET", "/invoices/" + invoice, null).body(), "220");
  }

  @Test
  void invoiceReplacementCopiesNewSourcesAndUnbillsRemovedSource() {
    String flight = create("flight");
    String hotel = create("hotel");
    String invoice = invoice(flight);
    Reply replaced = request("PATCH", "/invoices/" + invoice, ids(hotel));
    assertThat(replaced.status()).isEqualTo(200);
    total(replaced.body(), "331.70");
    assertThat(replaced.body().get("transactions").size()).isEqualTo(1);
    assertThat(request("DELETE", "/transactions/" + flight, null).status()).isEqualTo(204);
    assertThat(request("DELETE", "/transactions/" + hotel, null).status()).isEqualTo(409);
  }

  @Test
  void uninvoicedPatchReplacesListsAndMetadataAndDeleteCascades() {
    String flight = create("flight");
    ObjectNode patch =
        mapper.createObjectNode().put("total", 230).put("occurred_at", "2026-03-12T06:40:00Z");
    patch.set(
        "tax_lines",
        mapper
            .createArrayNode()
            .add(mapper.createObjectNode().put("name", "VAT").put("amount", 10).put("rate", .1)));
    patch.set("fee_lines", mapper.createArrayNode());
    patch.set("metadata", seed("flight").get("metadata"));
    Reply reply = request("PATCH", "/transactions/" + flight, patch);
    assertThat(reply.status()).isEqualTo(200);
    total(reply.body(), "230");
    assertThat(request("GET", "/transactions/" + flight, null).body().get("fee_lines")).isEmpty();
    assertThat(request("DELETE", "/transactions/" + flight, null).status()).isEqualTo(204);
    assertThat(count("transaction_tax_lines")).isZero();
    assertThat(count("transaction_fee_lines")).isZero();
  }

  @Test
  void paginationIsBoundedForBothResources() {
    for (int i = 0; i < 55; i++) {
      ObjectNode seed = seed("trip_fee").put("external_id", "page-" + i);
      Reply created = request("POST", "/transactions", seed);
      assertThat(created.status()).isEqualTo(201);
      invoice(created.body().get("id").asText());
    }
    for (String resource : List.of("transactions", "invoices")) {
      JsonNode page = request("GET", "/" + resource, null).body();
      assertThat(page.get("limit").asInt()).isEqualTo(50);
      assertThat(page.get("items").size()).isEqualTo(50);
      assertThat(
              request("GET", "/" + resource + "?limit=10&offset=50", null)
                  .body()
                  .get("items")
                  .size())
          .isEqualTo(5);
      assertThat(request("GET", "/" + resource + "?limit=1000", null).status()).isEqualTo(400);
      assertThat(request("GET", "/" + resource + "?offset=-1", null).status()).isEqualTo(400);
    }
  }

  @Test
  void invalidInputsNeverPersistPartialRecords() {
    ObjectNode flight = seed("flight");
    flight.put("total", new BigDecimal("220.001"));
    assertThat(request("POST", "/transactions", flight).status()).isEqualTo(400);
    ObjectNode invalid = seed("duplicate_flight").put("external_id", "new-empty-coupons");
    assertThat(request("POST", "/transactions", invalid).status()).isEqualTo(400);
    assertThat(request("POST", "/transactions", seed("trip_fee").put("type", "unknown")).status())
        .isEqualTo(400);
    ObjectNode fee = seed("trip_fee");
    ((ObjectNode) fee.get("metadata")).put("fee_kind", "unknown");
    assertThat(request("POST", "/transactions", fee).status()).isEqualTo(400);
    ObjectNode nullLine = seed("flight");
    nullLine.putArray("tax_lines").addNull();
    assertThat(request("POST", "/transactions", nullLine).status()).isEqualTo(400);
    assertThat(count("transactions")).isZero();
    assertThat(count("transaction_tax_lines")).isZero();
    assertThat(request("GET", "/transactions/missing", null).status()).isEqualTo(404);
    assertThat(
            request("PATCH", "/transactions/missing", mapper.createObjectNode().put("total", 1))
                .status())
        .isEqualTo(404);
    assertThat(request("DELETE", "/transactions/missing", null).status()).isEqualTo(404);
    assertThat(request("GET", "/invoices/missing", null).status()).isEqualTo(404);
    assertThat(request("PATCH", "/invoices/missing", ids("missing")).status()).isEqualTo(404);
    assertThat(request("DELETE", "/invoices/missing", null).status()).isEqualTo(404);
  }

  private List<Reply> race(Callable<Reply> first, Callable<Reply> second) throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      CyclicBarrier barrier = new CyclicBarrier(2);
      Future<Reply> a =
          executor.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return first.call();
              });
      Future<Reply> b =
          executor.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.call();
              });
      return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    }
  }

  @Test
  void overlappingDuplicatePostsLeaveExactlyOneRow() throws Exception {
    List<Reply> results =
        race(
            () -> request("POST", "/transactions", seed("flight")),
            () -> request("POST", "/transactions", seed("flight")));
    assertThat(results.stream().map(Reply::status)).containsExactlyInAnyOrder(201, 409);
    String created =
        results.stream()
            .filter(r -> r.status() == 201)
            .findFirst()
            .orElseThrow()
            .body()
            .get("id")
            .asText();
    String existing =
        results.stream()
            .filter(r -> r.status() == 409)
            .findFirst()
            .orElseThrow()
            .body()
            .get("existing_id")
            .asText();
    assertThat(existing).isEqualTo(created);
    assertThat(count("transactions")).isEqualTo(1);
    assertThat(count("transaction_tax_lines")).isEqualTo(1);
    assertThat(count("transaction_fee_lines")).isEqualTo(1);
  }

  @Test
  void overlappingPatchesDoNotTearChargeLists() throws Exception {
    String id = create("flight");
    ObjectNode a = mapper.createObjectNode().put("total", 250);
    a.putArray("tax_lines").add(mapper.createObjectNode().put("name", "A").put("amount", 10));
    a.putArray("fee_lines").add(mapper.createObjectNode().put("name", "A").put("amount", 20));
    ObjectNode b = mapper.createObjectNode().put("total", 300);
    b.putArray("tax_lines").add(mapper.createObjectNode().put("name", "B").put("amount", 30));
    b.putArray("fee_lines").add(mapper.createObjectNode().put("name", "B").put("amount", 40));
    assertThat(
            race(
                    () -> request("PATCH", "/transactions/" + id, a),
                    () -> request("PATCH", "/transactions/" + id, b))
                .stream()
                .map(Reply::status))
        .containsOnly(200);
    JsonNode value = request("GET", "/transactions/" + id, null).body();
    String name = value.at("/tax_lines/0/name").asText();
    assertThat(value.at("/fee_lines/0/name").asText()).isEqualTo(name);
    total(value, name.equals("A") ? "250" : "300");
  }

  @Test
  void invoiceCreationAndSourcePatchSerialize() throws Exception {
    String id = create("flight");
    List<Reply> results =
        race(
            () -> request("POST", "/invoices", ids(id)),
            () ->
                request(
                    "PATCH", "/transactions/" + id, mapper.createObjectNode().put("total", 250)));
    assertThat(results.get(0).status()).isEqualTo(201);
    assertThat(results.get(1).status()).isIn(200, 409);
    String invoice = results.get(0).body().get("id").asText();
    total(
        request("GET", "/invoices/" + invoice, null).body(),
        results.get(1).status() == 200 ? "250" : "220");
  }

  @Test
  void concurrentInvoicePatchAndDeleteNeverLeaveOrphanSnapshots() throws Exception {
    String flight = create("flight");
    String hotel = create("hotel");
    String invoice = invoice(flight);
    List<Reply> results =
        race(
            () -> request("PATCH", "/invoices/" + invoice, ids(hotel)),
            () -> request("DELETE", "/invoices/" + invoice, null));
    assertThat(results.get(0).status()).isIn(200, 404);
    assertThat(results.get(1).status()).isEqualTo(204);
    assertThat(count("invoices")).isZero();
    assertThat(count("invoice_lines")).isZero();
    assertThat(count("invoice_tax_lines")).isZero();
    assertThat(count("transactions")).isEqualTo(2);
  }

  @Test
  void singleIdShapeIsSupportedAndEqualChargeRowsKeepTheirPositions() {
    ObjectNode body = seed("flight");
    body.putArray("fee_lines")
        .add(mapper.createObjectNode().put("name", "YQ").put("amount", 10))
        .add(mapper.createObjectNode().put("name", "YQ").put("amount", 10));
    String id = request("POST", "/transactions", body).body().get("id").asText();
    Reply invoice =
        request("POST", "/invoices", mapper.createObjectNode().put("transaction_id", id));
    assertThat(invoice.status()).isEqualTo(201);
    assertThat(invoice.body().at("/transactions/0/fee_lines").size()).isEqualTo(2);
    assertThat(invoice.body().at("/fee_rollup/0/amount").decimalValue()).isEqualByComparingTo("20");
  }
}
