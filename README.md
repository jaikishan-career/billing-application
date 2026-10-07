# Travel transactions and snapshot invoices

Java 21, Spring Boot, Spring JDBC, PostgreSQL, and Flyway. No UI or external vendor calls.

## Run

Install JDK 21, Maven 3, and PostgreSQL (tested against PostgreSQL 18). Create an empty database called `navaan`, or set `DB_URL` to another dedicated database. The database user needs permission to create tables and indexes. Flyway applies the schema automatically at startup.

Database URL, username, and password are together in the root `application-local.properties`, which Spring loads automatically and `.gitignore` excludes. The credential file is not included in the submission. Copy the committed template and fill in your own local PostgreSQL password:

```powershell
cd D:\practice\Navaan\BillingApplication
Copy-Item application-local.properties.example application-local.properties
```

Run from the `BillingApplication` project directory, where `pom.xml` and `src/` live:

```powershell
cd D:\practice\Navaan\BillingApplication
mvn spring-boot:run
```

The single run command is **`mvn spring-boot:run`**. The API is at `http://localhost:8080`. Maven downloads dependencies declared in `pom.xml` on first use. Without the local file, `DB_URL`, `DB_USER`, and `DB_PASSWORD` provide configuration. To override a local file at runtime, use Spring's `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` environment variables. Maven uses its normal local dependency cache; generated build output and local credentials are excluded from Git.

Optional Docker database: set `DB_PASSWORD`, then `docker compose up -d`. Compose provisions `travel_invoice`; set the URL in `application-local.properties` to `jdbc:postgresql://localhost:5432/travel_invoice`. This is an alternative to your locally installed PostgreSQL, and needs port 5432 available.

For a packaged executable: `mvn package`, then `java -jar target/travel-invoice-1.0.0.jar` from the project directory so the same local properties file is loaded. Database rows survive API restarts.

## Tests

After configuring the local properties file and starting PostgreSQL, the single command is:

```powershell
mvn test
```

The HTTP integration suite starts the real server on a random port and uses PostgreSQL, never an in-memory database. Each run uses a uniquely named `billing_test_*` schema, which is dropped on completion. The test user must have permission to create schemas. Tests truncate only tables in that isolated schema. Interrupted runs may leave an isolated test schema behind.

`TEST_DB_URL`, `TEST_DB_USER`, and `TEST_DB_PASSWORD` can override the application settings for tests. Otherwise tests use `DB_URL`, `DB_USER`, and `DB_PASSWORD`, falling back to the same local properties file (default database `navaan`, user `postgres`). Coverage includes all five seeds, invoice totals and rollups, duplicate identity and races, snapshot isolation from direct source changes, billed PATCH/DELETE restrictions, atomic invoice replacement, mixed currencies, missing/repeated IDs, pagination, validation, cascading deletes, concurrent transaction edits, and invoice PATCH/DELETE races.

## API conventions

- Create returns 201; successful GET/PATCH returns 200; DELETE returns 204. Missing resources return 404.
- Duplicate `external_id` returns 409 with `existing_id`, even if the rest of that duplicate body differs. The database unique constraint handles overlapping writes; lookup after rollback retrieves the winning ID.
- Duplicate detection takes precedence over full metadata validation for an existing external ID. New flights require at least one coupon; the supplied `duplicate_flight` fixture intentionally has none.
- Money is `BigDecimal` / `DECIMAL(19,2)`, nonnegative, at most two fractional digits. No silent rounding. `total` is gross: taxes and fees are already included and are never added twice.
- Rates are fractions (0.07 = 7%), optional, 0..1, at most six fractional digits. Tax rollups group by exact name and numerical rate; absent rate is its own group. Fee rollups group by exact name. Names are case-sensitive.
- Transaction PATCH supports `total`, `tax_lines`, `fee_lines`, `metadata`, and `occurred_at`. Omitted fields remain unchanged; supplied lists and metadata replace the whole value. Identity, type, and currency cannot change.
- PATCH/DELETE on a transaction referenced by any invoice returns 409, without modifications. It becomes editable again after its last invoice reference is removed.
- An invoice accepts either `transaction_id` or `transaction_ids` (1..100). Repeated IDs and mixed currencies return 409. Rebilling the same source on another invoice is permitted by this brief.
- Invoice PATCH replaces its complete set of transaction snapshots. Failed replacement leaves the original invoice intact. Invoice DELETE preserves source transactions.
- Lists return `{items, limit, offset}` summaries, ordered by ID. Default limit 50, maximum 100; invalid limits/negative offsets return 400. Use GET by ID for full details. Pagination is executed in SQL.
- Type-specific metadata is validated against Java records, then preserved as JSONB, including optional extra fields. Taxes and fees are separate tables. Each list supports at most 1000 entries.
- `/health` runs a database connectivity query and returns `{"status":"ok"}`.

## Curl examples

These examples use Bash and `jq` to capture generated IDs (Git Bash or WSL on Windows). In PowerShell use `curl.exe` and save dynamic JSON bodies to files; avoid the `curl` alias in Windows PowerShell. Run from the project directory. `examples/*.json` are copies of the supplied fixtures. The original test fixtures are included under `fixtures/task-b/`, making this folder self-contained.

```bash
BASE=http://localhost:8080
# Create each transaction type; Navan trip and agent-call fees are separate transactions.
FLIGHT=$(curl -fsS "$BASE/transactions" -H 'Content-Type: application/json' -d @examples/flight.json | jq -r .id)
HOTEL=$(curl -fsS "$BASE/transactions" -H 'Content-Type: application/json' -d @examples/hotel.json | jq -r .id)
RAIL=$(curl -fsS "$BASE/transactions" -H 'Content-Type: application/json' -d @examples/rail.json | jq -r .id)
TRIP=$(curl -fsS "$BASE/transactions" -H 'Content-Type: application/json' -d @examples/trip_fee.json | jq -r .id)
CALL=$(curl -fsS "$BASE/transactions" -H 'Content-Type: application/json' -d @examples/agent_call_fee.json | jq -r .id)

# Same external_id again: 409 and existing_id = $FLIGHT.
curl -i "$BASE/transactions" -H 'Content-Type: application/json' -d @examples/duplicate_flight.json
# Only one flight row (run against a clean database).
curl -fsS "$BASE/transactions?limit=100&offset=0" | jq '[.items[] | select(.external_id == "flight-TXL-LHR-2026-03-11")] | length'

curl -fsS "$BASE/transactions/$FLIGHT" | jq .
curl -fsS "$BASE/transactions?limit=10&offset=0" | jq .
curl -i "$BASE/transactions?limit=1000" # 400

# Single transaction ID is supported.
SINGLE=$(curl -fsS "$BASE/invoices" -H 'Content-Type: application/json' \
  -d "{\"transaction_id\":\"$FLIGHT\"}" | jq -r .id)
curl -i -X DELETE "$BASE/invoices/$SINGLE" # Source stays present.

# Mixed flight/hotel/rail/Navan-fee trip, grand total 685.70 EUR.
INVOICE=$(curl -fsS "$BASE/invoices" -H 'Content-Type: application/json' \
  -d "{\"transaction_ids\":[\"$FLIGHT\",\"$HOTEL\",\"$RAIL\",\"$TRIP\",\"$CALL\"]}" | jq -r .id)
curl -fsS "$BASE/invoices/$INVOICE" | jq .
curl -fsS "$BASE/invoices?limit=10&offset=0" | jq .

# Both operations are blocked while the flight is invoiced: 409.
curl -i -X PATCH "$BASE/transactions/$FLIGHT" -H 'Content-Type: application/json' -d @examples/patch-transaction.json
curl -i -X DELETE "$BASE/transactions/$FLIGHT"

# Replace invoice with hotel only, atomically rebuild its snapshots.
curl -i -X PATCH "$BASE/invoices/$INVOICE" -H 'Content-Type: application/json' \
  -d "{\"transaction_ids\":[\"$HOTEL\"]}"

# Flight no longer billed: patch succeeds, then delete succeeds.
curl -i -X PATCH "$BASE/transactions/$FLIGHT" -H 'Content-Type: application/json' -d @examples/patch-transaction.json
curl -i -X DELETE "$BASE/transactions/$FLIGHT"
curl -i -X DELETE "$BASE/invoices/$INVOICE"
curl -fsS "$BASE/transactions/$HOTEL" | jq . # Still exists.
curl -fsS "$BASE/health"
```

Submission requires your Git repository URL and commit SHA. Initialize and push a Git repository from this project directory when ready to submit.



## Java style

Source and test code follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html). Spotless runs the pinned Google Java formatter; Checkstyle uses its bundled Google rules without suppressions. Both checks run in Maven's `validate` phase, so `mvn test`, `mvn package`, and `mvn verify` enforce formatting, explicit imports, braces, naming, and public API documentation. `.editorconfig` supplies matching editor defaults.

Apply formatting after edits:

```powershell
mvn spotless:apply
```

Check style without starting the HTTP tests:

```powershell
mvn validate
```

Run style checks, PostgreSQL HTTP tests, and package the service:

```powershell
mvn verify
```


