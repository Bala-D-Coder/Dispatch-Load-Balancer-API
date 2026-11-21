# Dispatch Load Balancer API

A Spring Boot service that allocates delivery orders to a capacity-limited fleet and returns a
dispatch plan. Orders are assigned **HIGH priority first**, each to the **nearest feasible vehicle**
by Haversine distance measured from that vehicle's current route tip; every vehicle's route is then
re-ordered by nearest-neighbour and reported as a **round trip** with the leg-by-leg arithmetic
exposed. Loads never exceed capacity, and whatever cannot be placed is returned with a machine-
readable reason instead of vanishing. REST only, no UI, Postman-testable out of the box.

The interesting property is that the sample fleet is smaller than the sample demand (645 kg of
orders, 500 kg of vehicles), so a "perfect" plan is impossible by design: the API has to be correct
about *what it refuses to carry and why*, which is most of what this exercise is testing.

- **Reference:** [`API.md`](API.md) — every field, every error, verbatim examples
- **Run it:** [`SETUP.md`](SETUP.md) — prerequisites, configuration, troubleshooting
- **Try it:** `postman/dispatch-load-balancer.postman_collection.json` — importable, assertions on
  every request
- **Docs served by the app:** `http://localhost:8080/swagger-ui.html`, contract at `/v3/api-docs`

---

## Quick start

Three commands, no configuration, from a fresh clone (PowerShell):

```powershell
docker compose up -d          # Postgres 16 on 127.0.0.1:5433 (dispatch / dispatch_user / dispatch_password)
.\mvnw.cmd spring-boot:run    # Flyway migrates on startup; app on :8080
Invoke-RestMethod http://localhost:8080/healthz      # -> @{status=UP}
```

This works with zero environment setup because the defaults in `application.yaml` and the values in
`compose.yaml` are the same four values — they were compared, not assumed.

Then the whole demo, which reproduces the plan the requirement document describes:

```powershell
$base = "http://localhost:8080/api/dispatch"
Invoke-RestMethod -Method Delete -Uri "$base/data"
Invoke-RestMethod -Method Post -Uri "$base/vehicles" -ContentType "application/json" -InFile "src\test\resources\sample\delhi-vehicles.json"
Invoke-RestMethod -Method Post -Uri "$base/orders"   -ContentType "application/json" -InFile "src\test\resources\sample\delhi-orders.json"
Invoke-RestMethod -Method Get -Uri "$base/plan" | ConvertTo-Json -Depth 6
```

Expected: 23 orders assigned / 495 kg / `"171 km"`, and 7 LOW-priority orders (150 kg) in
`unassignedOrders` with reason `NO_VEHICLE_WITH_SUFFICIENT_REMAINING_CAPACITY`. Those are the
numbers `DispatchPlanE2EIT` asserts, so if the README and the code ever disagree, the build goes
red.

### The API itself as a container

`compose.yaml` starts Postgres only — the app runs from source above. To build and run the shipped
image instead (it contains no database, so the three connection values are required):

```powershell
docker build -t dispatch-load-balancer-api .
docker run --rm -p 127.0.0.1:8080:8080 `
  -e DB_URL="jdbc:postgresql://host.docker.internal:5433/dispatch" `
  -e DB_USERNAME=dispatch_user -e DB_PASSWORD=dispatch_password `
  --add-host=host.docker.internal:host-gateway dispatch-load-balancer-api
```

`localhost` inside a container is the container, not your laptop, so `DB_URL` uses
`host.docker.internal` to reach the same Postgres `docker compose up -d` published on port 5433. If
you keep a `.env` (from `.env.example`), `--env-file .env` works in place of the `-e` flags —
it just has to exist, since it is not committed. Already-running app on 8080? Publish another host
port (`-p 127.0.0.1:18080:8080`); `SETUP.md` has the Windows error text this produces.

Verified on this machine: `/healthz` UP 8 s after `docker run`, the image's `HEALTHCHECK` reports
`healthy` at ~20 s, the process runs as `appuser`, and the Bangalore sample posted through the
container returns `10.120580497298127` / `7.936632638843489` km — bit-identical to what
`DispatchServiceUpsertIT` asserts against Postgres directly.

Multi-stage (`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-alpine`, 413 MB, ~2.5 min from
a cold dependency cache), runs as the non-root `appuser`, and its `HEALTHCHECK` polls `/healthz`.
The build deliberately does **not** run the tests: every `*IT` needs a Docker daemon inside the build
container, which is a CI concern, so `.github/workflows/ci.yml` runs `./mvnw -B verify` and *then*
`docker build`. Deployment uses the same file: `deploy.yml` builds it on a GitHub runner, pushes the
image to Docker Hub, and Render runs that image — the build is never handed to the host.

## Prerequisites and stack

| | |
|---|---|
| Language | Java 21 (Temurin 21.0.11 used) |
| Framework | Spring Boot **4.2.0** (`spring-boot-starter-webmvc`, `-data-jpa`, `-validation`, `-flyway`, `-actuator`) |
| Build | Maven wrapper (`only-script` 3.3.4 → Maven 3.10.1); no local Maven install |
| Database | PostgreSQL 16-alpine via `compose.yaml`; driver 42.7.14 |
| Migration | Flyway 12.11.0, one migration, `ddl-auto: validate` |
| ORM | Hibernate 7.4.15 (Spring Data JPA repositories) |
| Mapping | MapStruct 1.6.3 + Lombok 1.18.48 (`lombok-mapstruct-binding` 0.2.0) |
| API docs | springdoc-openapi 3.2.0 (the Boot 4 line), Swagger UI 5.34.0 |
| Tests | JUnit 6.1.3, AssertJ 3.27.7, Mockito 5.24.0, Testcontainers 2.0.5, `@WebMvcTest` |
| Coverage | JaCoCo 0.8.16 — report + `check` gate bound to `verify` (thresholds in [Testing](#testing)) |
| Containers | Docker Desktop (needed for `.\mvnw.cmd test`; see [Testing](#testing)), plus a `Dockerfile` for the image itself |

Boot 4 moved a few test annotations between packages and switched Jackson 3 in as the default
mapper; where this repo differs from Boot 3 tutorials, `SETUP.md` and the code comments say so.

## Configuration

Everything the app reads is four keys, all defaulted:

| Property | Default | Source |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/dispatch` | `DB_URL` |
| `spring.datasource.username` | `dispatch_user` | `DB_USERNAME` |
| `spring.datasource.password` | `dispatch_password` | `DB_PASSWORD` |
| server port | `8080` | Boot default (not overridden) |

Using the bundled compose file needs **nothing**. To point at a different Postgres, either set OS
environment variables or copy `.env.example` to `.env` (gitignored, three keys, never committed):

```powershell
Copy-Item .env.example .env
```

`application.yaml` imports it as a properties file —
`spring.config.import: "optional:file:.env[.properties]"` — so there is no dotenv dependency and a
clone with no `.env` still starts. Precedence is Spring Boot's usual order: **JVM `-D` and OS
environment variables beat `.env`**, which is what makes a deployment environment authoritative over
a stale local file. The file is looked up relative to the working directory, so run from the module
root. Six tests in `DotEnvImportTest` pin exactly these claims.

## API

| Method | Path | Success | What it does |
|---|---|---|---|
| `POST` | `/api/dispatch/orders` | `200` ack | Ingest orders; upsert by `orderId`; a duplicate *inside one batch* is `400` |
| `POST` | `/api/dispatch/vehicles` | `200` ack | Ingest the fleet; upsert by `vehicleId`; same duplicate rule |
| `GET` | `/api/dispatch/plan` | `200` plan | Rebuild the plan from the database — no cache, no persisted plan |
| `DELETE` | `/api/dispatch/data` | `200` counts | **Additive** reset helper so demos and Postman runs are repeatable |

Every failure — all eight kinds — answers with the same seven-key envelope whose `code` field is the
stable thing to switch on. Full contract, examples and error matrix: [`API.md`](API.md).

## Worked example (real output, not a sketch)

The specification's own 2-order / 2-vehicle sample, posted to a running instance:

```json
{"vehicles": [
  {"vehicleId": "VEH001", "capacity": 100, "currentLatitude": 12.9716, "currentLongitude": 77.6413, "currentAddress": "Indiranagar, Bangalore, Karnataka, India"},
  {"vehicleId": "VEH002", "capacity": 150, "currentLatitude": 13.0674, "currentLongitude": 80.2376, "currentAddress": "T Nagar, Chennai, Tamil Nadu, India"}
]}
```

```json
{"orders": [
  {"orderId": "ORD001", "latitude": 12.9716, "longitude": 77.5946, "address": "MG Road, Bangalore, Karnataka, India", "packageWeight": 10, "priority": "HIGH"},
  {"orderId": "ORD002", "latitude": 13.0827, "longitude": 80.2707, "address": "Anna Salai, Chennai, Tamil Nadu, India", "packageWeight": 20, "priority": "MEDIUM"}
]}
```

`GET /api/dispatch/plan`:

```json
{"dispatchPlan": [
  {"vehicleId": "VEH001", "currentAddress": "Indiranagar, Bangalore, Karnataka, India", "totalLoad": 10,
   "totalDistance": "10 km", "totalDistanceKm": 10.120580497298127,
   "legs": [
     {"fromAddress": "Indiranagar, Bangalore, Karnataka, India", "toAddress": "MG Road, Bangalore, Karnataka, India", "distanceKm": 5.060290248649063},
     {"fromAddress": "MG Road, Bangalore, Karnataka, India", "toAddress": "Indiranagar, Bangalore, Karnataka, India", "distanceKm": 5.060290248649063}],
   "assignedOrders": [
     {"orderId": "ORD001", "latitude": 12.9716, "longitude": 77.5946, "address": "MG Road, Bangalore, Karnataka, India", "packageWeight": 10, "priority": "HIGH"}]},
  {"vehicleId": "VEH002", "currentAddress": "T Nagar, Chennai, Tamil Nadu, India", "totalLoad": 20,
   "totalDistance": "8 km", "totalDistanceKm": 7.936632638843489,
   "legs": [
     {"fromAddress": "T Nagar, Chennai, Tamil Nadu, India", "toAddress": "Anna Salai, Chennai, Tamil Nadu, India", "distanceKm": 3.9683163194217443},
     {"fromAddress": "Anna Salai, Chennai, Tamil Nadu, India", "toAddress": "T Nagar, Chennai, Tamil Nadu, India", "distanceKm": 3.9683163194217443}],
   "assignedOrders": [
     {"orderId": "ORD002", "latitude": 13.0827, "longitude": 80.2707, "address": "Anna Salai, Chennai, Tamil Nadu, India", "packageWeight": 20, "priority": "MEDIUM"}]}
 ],
 "unassignedOrders": [],
 "totals": {"assignedOrderCount": 2, "unassignedOrderCount": 0, "totalLoadKg": 30,
            "totalDistanceKm": 18.057213136141616, "totalDistance": "18 km"}}
```

**Why this says `"10 km"` where the spec's example says `"5 km"`.** The spec's sample response shows
one-way, illustrative figures; the requirement is "total travel distance for each vehicle", and a
vehicle has to get back, so `totalDistance` here is the round trip: `5.060290` km out and the same
distance home = `10.120580` km → `"10 km"`. `legs[]` is published precisely so a reviewer can check
that arithmetic without reading the code. This is the one place the output deliberately differs from
the document's example, and it is the kind of thing worth flagging rather than quietly "fixing".

## Assumptions and design decisions

My interpretation of the brief, offered for correction — each row is implemented and tested, not
aspirational:

| # | Decision | Reasoning |
|---|---|---|
| D1 | Priority order is `HIGH → MEDIUM → LOW`, then heavier packages first, then `orderId` | The spec fixes only "high first"; the rest is needed to make the output deterministic and re-runnable. |
| D2 | Vehicle choice minimises Haversine distance from the vehicle's **current route tip**, not its original garage position | "Minimise total travel distance" has to be measured against where the vehicle will actually be; ties break to more remaining capacity, then lower `vehicleId`. |
| D3 | `totalDistance` is a **round trip**, and `legs[]` + `totalDistanceKm` are added so it is auditable | See the worked example above. |
| D4 | No plan cache and no persisted plan; `GET /plan` recomputes from the database every call | Stale plans are a correctness bug; the recompute costs ~0.06 s at 5,000 orders (measured below), which is not worth invalidation logic. |
| D5 | Distance is Haversine on a 6,371 km sphere, straight-line | The spec names the formula. Road distance would need a routing provider; noted under [not done](#what-i-did-not-do-and-why). |
| D6 | Duplicate id **within one batch** → `400 DUPLICATE_IN_BATCH`; duplicate **across batches** → upsert | Two conflicting rows for one key in a single request is caller error; re-posting later is ordinary data correction, and "efficient database operations (upsert)" asks for it. |
| D7 | Validation ranges (weights ≤ 10 t, capacities ≤ 100 t, coordinate bounds, and the two column widths 64 / 512) live in the code, and the schema has no `CHECK`/`NOT NULL` beyond primary keys | One validation authority. Two means the API can reject what the database accepts, or vice versa — with the widths it was literally that: an over-long address produced a 500. |
| D8 | Only vehicles that received a stop appear in `dispatchPlan`; unassignable orders are always reported | Matches the spec's own example shape, and `totals` proves nothing went missing. |
| D9 | `DELETE /api/dispatch/data` is additive, not spec'd | Without a reset, a Postman run degrades into an accumulation of earlier submissions. |
| D10 | Specification strings (`"Delivery orders accepted."`, `"5 km"` format) are reproduced verbatim | The ack text and the `"<n> km"` shape are part of the given contract; additive fields carry the extra information instead of changing existing ones. |
| D11 | `GET /plan` on an empty database is `404`; vehicles-only or orders-only is `200` with an explained empty plan | "Nothing has been posted yet" is a different state from "there is no fleet", and the second is an answer the caller wants. |

## Algorithm

Input: every order and vehicle currently in the database. Three stages, all in
`service/dispatch/handler/`.

1. **Sort** (`OrderSorter`): priority rank, then `packageWeight` descending, then `orderId`. This is
   the order in which orders *compete* for capacity.
2. **Assign** (`GreedyAssignmentStrategy`): for each order in that order, scan the fleet (iterated in
   `vehicleId` order so the result is deterministic) for vehicles with `remaining >= packageWeight`,
   and pick the one whose **route tip** is nearest by Haversine. Ties → larger remaining capacity →
   lower `vehicleId`. The chosen vehicle's tip moves to that order's coordinates and its remaining
   capacity drops. If nothing fits, the order is unassigned with
   `EXCEEDS_ALL_VEHICLE_CAPACITIES` (impossible even for an empty vehicle) or
   `NO_VEHICLE_WITH_SUFFICIENT_REMAINING_CAPACITY` (the fleet filled up first), or
   `NO_VEHICLES_AVAILABLE` when the fleet is empty.
3. **Route** (`RoutePlanner`): per vehicle, greedy nearest-neighbour from its start position over the
   stops it won, then the leg home. `legs[]` is that chain; `totalDistanceKm` is its sum. Note the
   deliberate asymmetry: *selection* uses forward-only tip distance, the *reported* distance is the
   full round trip. Assignment heuristic vs. reported plan — not a contradiction.

`DistanceCache` memoises point-to-point distances on a key made of the four coordinates' IEEE-754
bit patterns. It used to render them to 6 decimals with `Locale.ROOT` instead, which measured worse
in every direction: ~1 µs of `String.format` per lookup against ~50 ns for the Haversine it skipped,
and two points ~1 cm apart sharing one cell (so the second query got the first pair's distance).
Bits are free to build, cannot alias, and cut the 5,000-order plan build from 128 ms to a 57–86 ms
spread — see the performance section.

**Complexity**, stated honestly: `O(n log n)` for the sort, `O(n · m)` for the candidate scan across
`m` vehicles, and `O(k²)` per vehicle for the nearest-neighbour ordering (`k` stops). So it is
linear in orders×vehicles and quadratic in stops-per-vehicle. It is a greedy heuristic, not an exact
VRP solver — no capacity-feasible solution is *claimed* to be optimal, and at this fleet size
(measured below) the gap is not what limits the system.

## Performance

`DispatchPerformanceIT` generates 5,000 orders × 20 vehicles, inserts them by JDBC batch, warms the
JVM once, then times three `buildPlan()` calls and logs each run. The quoted number is the
**uninstrumented** one, reproduced with the coverage agent switched off:

```powershell
.\mvnw.cmd test -Dtest=DispatchPerformanceIT "-Djacoco.skip=true"
```

```
PERF ingest:    77, 95, 118, 122 ms for 5000 orders / 20 vehicles
PERF buildPlan: median 63 ms over five JVMs — 57, 61, 63, 79, 86 ms   fence: < 2000 ms
```

The spread is the machine, not the code: the same build ranged 57–86 ms across separate JVMs on one
afternoon. Before the `DistanceCache` key change the identical test measured 128 ms (`[128, 133,
112]`) with earlier runs at 104, 101 and 89 ms, so the distributions do not overlap. The A/B that
isolates the change (one JVM, same 5,000 × 20 dataset, only the cache key differing) is in the review
record: **25.5 ms → 9.7 ms** for the algorithm phase, and 865 ms → 20 ms on a deliberately
coordinate-repeating batch.

So call it **~0.06 s for 5,000 orders across a 20-vehicle fleet (worst observed 0.09 s), and 23–35×
inside the fence the test asserts** (`assertThat(median).isLessThan(2_000)`) — a logged assertion
target, not a badge, so it cannot rot into a stale claim.

One caveat that would otherwise look like a contradiction: under the full `.\mvnw.cmd clean verify`
the JaCoCo **agent is attached**, and instrumented bytecode is measurably slower in exactly this hot
loop — the same test just measured median 93 ms (`[112, 87, 93]`) with the agent on, and has logged
153 ms and 331 ms on colder runs. The gate stays green either way; the difference is instrumentation
overhead, not the algorithm. Machine-specific too: it says what this laptop did, not what yours will.

The fleet is deliberately ~10× over-subscribed, so the timed path includes assembling a large
`unassignedOrders` list, not just the happy one.

## Design patterns, and where each actually lives

| Pattern | File(s) | Why it earns its place |
|---|---|---|
| Strategy | `handler/AssignmentStrategy` + `GreedyAssignmentStrategy`, bean wired in `config/DispatchConfig` | The heuristic is the one thing likely to be replaced (OR-Tools slot); nothing above it changes. |
| Pure core / ports-and-adapters in spirit | the whole `handler/` package has **no Spring imports** | The algorithm is unit-tested without a container, in milliseconds. |
| Repository | `repository/OrderRepository`, `VehicleRepository` | Spring Data; natural-key `save` gives the upsert. |
| Facade / service layer | `service/dispatch/DispatchService` + `impl` | The controller's only collaborator; returns DTOs, never entities. |
| Mapper (compile-time) | `mapper/DispatchMapper` (MapStruct, `componentModel = "spring"`, `unmappedTargetPolicy = ERROR`) | A renamed field is a build error, not a `null` in production. |
| Immutable DTOs / value objects | `dto/**` (all `record`s), `entity/common/GeoPoint` `@Embeddable` | Wire contract independent of the persistence model. |
| Memoizer | `handler/DistanceCache` | Same coordinate pairs recur constantly in a dense metro sample; keyed on exact bit patterns so a hit is the same pair, always. |
| Single choke point for errors | `exception/GlobalExceptionHandler` (`@RestControllerAdvice`, deliberately unscoped) | One place owns the seven-key envelope; a package-scoped advice cannot even see 405/404. |
| Static factory | `AckResponse.orders(...)`, `ClearDataResponse.of(...)`, `UnassignedOrderDto.of(...)` | The spec's verbatim strings exist in exactly one place each. |
| Builder | Lombok `@Builder` on the two entities | Readable fixtures in tests. |

## Testing

151 tests, all green (`.\mvnw.cmd test`; the wall clock is dominated by Testcontainers start-up —
~40 s warm, ~130 s cold). Layered so a failure says which layer broke:

| Layer | Class | Tests | What it pins |
|---|---|---|---|
| Unit | `DistanceUtilsTest` | 11 | Golden distances (CP↔Karol Bagh 14.442261 km), symmetry, antimeridian wrap, near-antipodal incl. the swept worst-case input, sign handling, monotonicity |
| Unit | `PriorityTest` | 3 | Rank order |
| Unit | `OrderRequestValidationTest` / `VehicleRequestValidationTest` | 10 / 6 | Every constraint message and boundary, including the column widths (512/513, 64/65) |
| Unit | `JsonBindingTest` | 7 | Unknown-field rejection, enum strictness, batch shape |
| Unit | `OrderSorterTest` | 4 | Priority → weight desc → id |
| Unit | `DistanceCacheTest` | 5 | Cached value == direct value, hit counting, coordinates 1 cm apart do not share an entry |
| Unit | `GreedyAssignmentStrategyTest` | 14 | The Delhi overflow invariant in memory, capacity never exceeded, tie-breakers |
| Unit | `RoutePlannerTest` | 7 | Round-trip legs, single-stop and empty cases |
| Unit | `SampleDataValidationTest` | 5 | The committed sample JSON == the fixtures the unit tests use (third transcription check) |
| Unit | `DispatchMapperTest` | 5 | DTO ↔ entity ↔ planned-model conversions |
| Unit | `DispatchServiceImplTest` | 12 | Ingestion semantics with mocked repositories |
| Unit | `DotEnvImportTest` | 6 | `.env` really loads, `optional:` is load-bearing, `-D` wins |
| Unit | `PostmanCollectionTest` | 5 | The Postman collection == the committed samples; every error request asserts its `code` |
| Slice | `DispatchControllerTest` (`@WebMvcTest`) | 19 | The whole error matrix E1–E7 through the servlet chain |
| Slice | `OrderRepositoryIT` / `VehicleRepositoryIT` (`@DataJpaTest` + Testcontainers) | 8 / 3 | Real SQL, upsert, `findAllById` batching |
| Integration | `DispatchServiceUpsertIT` | 3 | Cross-request upsert against Postgres, both sides of the API, with the re-planned distance golden |
| Integration | `DispatchApplicationTests` | 1 | The context boots, including the remapped `/healthz` config |
| E2E | `DispatchPlanE2EIT` | 6 | Golden Delhi through HTTP+Postgres; Bangalore spec shape; leg/round-trip self-consistency; byte-identical recompute |
| E2E | `DispatchFlowE2EIT` | 5 | Upsert, reset, 404/200 boundaries, over-long strings rejected at 400 before the driver |
| E2E | `ApplicationEndpointsIT` | 4 | `/healthz`, `/v3/api-docs`, `/swagger-ui.html` are really mapped, and the published schema carries the 512/64 widths |
| E2E perf | `DispatchPerformanceIT` | 2 | The measured number above + the invariant at 5,000 rows |

Container-free subset (no Docker needed):

```powershell
.\mvnw.cmd test "-Dtest=!*IT,!DispatchApplicationTests"   # 119 tests
```

**Coverage:** JaCoCo 0.8.16 is bound to `verify`, so `.\mvnw.cmd clean verify` writes
`target\site\jacoco\index.html` and fails the build when a threshold slips. Measured on the 150-test
suite above — LINE counters, with `lombok.config` marking generated accessors `@Generated` so
JaCoCo drops them from the counts:

| Scope | Covered / total lines | Gate |
|---|---|---|
| Whole application | 413 / 433 = **95.4 %** | ≥ 75 % |
| Algorithm + `util` (`service.dispatch.**`, `util`) | 203 / 203 = **100 %** | ≥ 85 % |
| `controller` | 7 / 7 = **100 %** | ≥ 80 % |
| `mapper` (generated `DispatchMapperImpl`) | 123 / 141 = 87.2 % | ungated |

The 20 uncovered lines are not hand-written logic: 18 are the `if (source == null) return null;`
guards MapStruct emits at the top of every generated method, 2 are `DispatchApplication.main()`,
which only the packaged jar runs. The gate therefore stops where the code I wrote begins — and the
one real gap JaCoCo found here was a genuine one: the *vehicle* half of the upsert (the
`mapper.update(request, managed)` branch at `DispatchServiceImpl:96`) had no test at all while the
order half did. `reingestVehicle_countsUpdatedAndReplacesValuesOnTheManagedEntity` and
`reingestVehiclePreservesCreatedAtAndCapacityFeedsNextPlan` are now the fence for it.

## Branches and pipelines

One branch ships, and the branch that ships is the branch that is tested. GitHub resolves workflow
files from the **pushed** branch, so a branch absent from every trigger list runs nothing — that is
the mechanism, not a convention:

| Branch | Push | Pull request into it |
|---|---|---|
| `deploy/production` | `deploy.yml`: **`verify` (the full suite + coverage gate) → migration-guard → `build-and-push` → deploy hook**, the last job waiting on the `production` environment's approval | `ci.yml` runs the suite, so a broken change is caught before it lands |
| `deploy/staging` | same pipeline, resolves to the staging environment — kept in the trigger list, unused until a staging service exists | — |
| `main` | **nothing** — a mirror branch, deliberately not named in any trigger | nothing |
| anything else (`Impl`, `local_me`, …) | nothing | `ci.yml` runs when the PR targets `deploy/production` |

The image is ours, not the host's: `build-and-push` runs the committed `Dockerfile` on a GitHub
runner and publishes it to Docker Hub under `latest`, `production` and `production-<short sha>`, and the
Render service is created as **Existing Image** pointing at `…/dispatch-load-balancer-api:production`.
Render re-resolves that tag only when it is told to deploy, never on its own, which is why the deploy
hook is the pipeline's last step — no hook call, no new code on the server. `provenance: false` is on
the push because an index carrying only attestations is not something Render can consume.

Two switches, both in GitHub settings rather than in YAML, so the pipeline can be wired before the
infrastructure exists: `vars.DEPLOY_ENABLED` must be the literal `true` or every job after `verify`
is skipped (tests still run, nothing ships, no red X over a missing secret), and the `production`
environment needs *Required reviewers* for the deploy job to pause for you. `concurrency` uses
`cancel-in-progress: false` here on purpose — cancelling a test run that a newer push superseded is
good hygiene, cancelling a half-finished deployment is not.

## Data model

Two tables, one Flyway migration (`V1__init_schema.sql`), natural keys as primary keys:

| `orders` | `order_id` PK, `latitude`, `longitude`, `address`, `package_weight`, `priority`, `created_at`, `updated_at` |
|---|---|
| `vehicles` | `vehicle_id` PK, `capacity`, `current_latitude`, `current_longitude`, `current_address`, `created_at`, `updated_at` |

No `CHECK` constraints and no `NOT NULL` beyond the primary keys: the code layer is the single
validation authority (D7 above). `ddl-auto: validate` means Hibernate checks the schema instead of
inventing it, and every new schema change gets its own migration. Assignment results are **not**
stored — the plan is derived, which is what makes D4 (no cache) safe.

## What I did not do, and why

| Not done | Why, and where it would go |
|---|---|
| OR-Tools / exact VRP / metaheuristics | The brief asks for capacity-feasible, priority-first, distance-minimising assignment. An exact solver buys output nobody can hand-verify, plus a heavy dependency. `AssignmentStrategy` is the seam it would slot into. |
| Clarke-Wright savings, two-opt route improvement | Improves the *route*, not the *assignment*, and would break the property that makes this reviewable: every leg is checkable with a calculator. |
| KD-tree / spatial index for nearest vehicle | `m` is a fleet size in the tens and distances are memoised; at 5,000 × 20 the whole scan costs ~0.06 s. Premature. |
| Plan caching | D4: correctness over latency, and the latency is the ~0.06 s median measured below. A `@Cacheable` wrapper on `buildPlan()` is the whole change if it ever matters. |
| Spring Security / API keys | The brief says Postman-testable with no setup. This is the first thing to add before any real exposure, and `DELETE /api/dispatch/data` in particular would need a role. |
| Road-network distances (OSRM/Google) | The spec names Haversine. Straight-line is stated, not hidden, and `legs[]` makes the assumption visible per stop. |
| DB `CHECK`/`NOT NULL` constraints | D7 — one validation authority, by explicit project rule. |
| Pagination or streaming of the plan | The response is bounded by rows in the database, and at 5,000 orders it is still ~100 ms. First thing to add when that stops being true. |
| Asynchronous ingestion / a job for plan building | Two endpoints, both fast enough synchronously. |
| Persistence of plans, drivers, live positions | Out of scope for the brief; would need an identity model this API does not have. |
| Deploying to a host | `Dockerfile`, `ci.yml` and `deploy.yml` exist and the image is verified locally. `deploy.yml` runs the suite first and then stops at `skipped` until the Docker Hub repository, the Render *Existing Image* service and the secrets exist and `vars.DEPLOY_ENABLED=true` — so this repo quotes no live URL, because there is none. |
| JaCoCo BRANCH thresholds | Deliberately LINE-only: the 20 uncovered lines are a code generator's null guards and `main()`, and a branch rule would gate code I did not write. |

## How to read the code in ten minutes

Open these in order; each one is short, and each answer is in the file rather than in a comment:

1. **`util/DistanceUtils`** — the whole formula, one method, no framework. Sanity-check it against
   `DistanceUtilsTest`'s golden numbers.
2. **`service/dispatch/handler/model/`** — `PlannedOrder`, `PlannedVehicle`, `VehicleRoute`,
   `RouteLeg`. These are what the algorithm actually thinks in.
3. **`handler/OrderSorter`** — the competition order for capacity, in one comparator.
4. **`handler/GreedyAssignmentStrategy`** — the 20-line loop that is the heart of the service:
   feasible vehicles only, nearest route tip, two deterministic tie-breaks, three reasons.
5. **`handler/RoutePlanner`** — nearest-neighbour plus the return leg; this is where `legs[]` comes
   from, so the reported distance can be checked against the stops.
6. **`service/dispatch/impl/DispatchServiceImpl`** — ingestion (duplicate-in-batch check, then
   load-and-copy upsert) and `buildPlan` (read everything, delegate, total it up).
7. **`controller/DispatchController`** — four methods, no logic, no `ResponseEntity` wrapping:
   success is always 200 + DTO, which is what makes step 8 complete.
8. **`exception/GlobalExceptionHandler`** — the entire failure surface of the API in one table of
   eight handlers.
9. **`DispatchPlanE2EIT`** — run it, then read its assertions: that file *is* the specification,
   executed against HTTP and Postgres.

Start at `handler/` rather than at the controller: that package is where the actual decision is
made, and it has no framework in it. Everything else is transport.

## Production notes

Ordered by what would bite first:

- **Auth before exposure**, and rate limits on the two POSTs — the batch size is unbounded today
  (only `packageWeight`/`capacity` ranges are enforced), so a 100 MB array is accepted today.
- **Indexes**: none beyond PKs; `orders` would want a partial index the moment the plan becomes
  persisted or filtered, and `SELECT … WHERE priority = ?` needs one before large fleets arrive.
- **Batch insertion**: ingestion uses `save` per element inside one transaction; a real ingestion
  path for large batches wants `saveAll` with `hibernate.jdbc.batch_size`, which is what the perf
  test's raw JDBC batch already demonstrates at 5,000 rows.
- **Observability**: only `/healthz` and `/info` are exposed today. A plan-build timer and an
  unassigned-order counter are the two metrics that would actually be watched.
- **Solver upgrade**: if the fleet grows to hundreds of vehicles, replace
  `GreedyAssignmentStrategy` (the KD-tree then earns its keep for the nearest-vehicle scan) rather
  than tuning the loop.
- **Time zones / locales**: the distance key holds bit patterns, not rendered numbers, so no locale
  can vary it; the same discipline is needed for any future timestamped data (render nothing that is
  only ever compared).
- **Deploying**: `.github/workflows/deploy.yml` is wired to one branch — `deploy/production` — and
  its first job is the test suite itself, so CI never publishes an image built from code that failed
  `verify`, and Render is only ever pointed at the image that run produced. It still cannot reach a
  server until a Docker Hub repository and a Render *Existing Image* service exist, the
  secrets below are in GitHub, and the repository variable `DEPLOY_ENABLED` is set to `true`; until
  then every job after `verify` reports `skipped`, which keeps a merge green-but-not-deployed instead
  of red on a missing key. It never assumes a hostname: the post-deploy probe reads
  `vars.HEALTHCHECK_URL`. Its `migration-guard` job is the enforceable half: it refuses a change set
  that adds a SQL file whose version is below the highest one already shipped, which is what keeps
  Flyway from skipping over an already-applied migration in production.
