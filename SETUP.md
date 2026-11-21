# Setup



Everything here was run on Windows 11 with PowerShell. Commands are marked `[powershell]`.

## 1. Prerequisites

| What | Version | Notes |
|---|---|---|
| JDK | 21 (Temurin 21.0.11 used here) | `java -version` |
| Maven | none needed | the wrapper is `only-script` (`mvnw.cmd`), which downloads Maven 3.10.1 on first run — so the **first** build needs internet |
| Docker Desktop | running | Postgres for the app (`compose.yaml`) and Testcontainers for the integration tests |
| IDE | IntelliJ / VS Code / Eclipse | **Lombok plugin + annotation processing enabled.** MapStruct generates a mapper implementation at compile time; with processing off the build fails on a missing `DispatchMapperImpl`, which looks like a code bug and is not one |

## 2. Clone and start the database

```powershell
git clone <your-fork-url> ; cd Dispatch-Load-Balancer-API
docker compose up -d
docker compose ps          # dispatch-postgres should be "healthy"
```

Compose publishes Postgres on `127.0.0.1:5433` only, with database `dispatch`, user
`dispatch_user`, password `dispatch_password`. Those four values are **also the defaults compiled
into `src/main/resources/application.yaml`**, which is why a fresh clone needs no configuration at
all. Flyway creates the schema on first start (`V1__init_schema.sql`); Hibernate runs in
`ddl-auto: validate` mode, so it checks the schema instead of writing it.

Those credentials are for throwaway local development. They are not secrets and are not used
anywhere else.

## 3. Configure — only if you are *not* using compose

Copy the example file and fill in your own Postgres:

```powershell
Copy-Item .env.example .env
notepad .env
```

```ini
DB_URL=jdbc:postgresql://localhost:5432/postgres
DB_USERNAME=your_user
DB_PASSWORD=your_password
```

`.env` is gitignored; never commit it, and never paste its contents into a chat or a ticket.

How it is loaded: `application.yaml` declares
`spring.config.import: "optional:file:.env[.properties]"`, so the file is read as a Java properties
file at startup — no dotenv dependency. `optional:` means a clone without a `.env` boots anyway.
`DotEnvImportTest` pins all four claims (the file is read from the working directory, keys become
properties, absence is tolerated, and a `-D` system property beats the file), so this section
cannot rot silently.

Two consequences worth knowing:

- The lookup is **relative to the working directory**. `.\mvnw.cmd spring-boot:run` runs from the
  module root and finds `.env`; an IDE run configuration whose working directory is somewhere else
  will not. Set the working directory to the module root (or use OS environment variables, which
  always win).
- Precedence is Spring Boot's normal order: JVM `-D` and OS environment variables outrank `.env`.
  That is what makes a deployment environment authoritative over a stale local file.

## 4. Run

```powershell
.\mvnw.cmd spring-boot:run
```

Then, in another terminal:

```powershell
Invoke-RestMethod http://localhost:8080/healthz        # -> @{status=UP}
Start-Process "http://localhost:8080/swagger-ui.html"   # interactive docs for all four endpoints
```

The Spring Boot Docker Compose integration is on the runtime classpath, so Boot may also start
`compose.yaml` for you if the container is not up yet; step 2 is the explicit route that always
works.

## 5. Load the demo data

Either use Postman (`postman/dispatch-load-balancer.postman_collection.json`, folders 0→4 — every
request carries assertions), or:

```powershell
$base = "http://localhost:8080/api/dispatch"
Invoke-RestMethod -Method Delete -Uri "$base/data"
Invoke-RestMethod -Method Post -Uri "$base/vehicles" -ContentType "application/json" -InFile "src\test\resources\sample\delhi-vehicles.json"
Invoke-RestMethod -Method Post -Uri "$base/orders"   -ContentType "application/json" -InFile "src\test\resources\sample\delhi-orders.json"
Invoke-RestMethod -Method Get -Uri "$base/plan" | ConvertTo-Json -Depth 6
```

Expect `23` orders assigned / `495 kg` / `171 km`, and `7` LOW-priority orders (`150 kg`) in
`unassignedOrders` — the Delhi sample is 645 kg of demand against a 500 kg fleet, so that shortfall
is a property of the data, not a bug. `API.md` documents every field of the answer.

## 6. Run the tests

```powershell
.\mvnw.cmd clean verify                                      # 151 tests + the coverage gate — needs Docker
.\mvnw.cmd test                                              # the same 151 tests, no packaging or gate
.\mvnw.cmd test "-Dtest=!*IT,!DispatchApplicationTests"      # 119 tests — no Docker needed
```

The full suite is 151 tests. 32 of them boot a Spring context against a real Postgres container
(Testcontainers), so `.\mvnw.cmd test` requires Docker Desktop to be running — Surefire is
configured to include `**/*IT.java` precisely so CI cannot silently skip them.

The second command is the container-free subset: it excludes every `*IT` class plus
`DispatchApplicationTests`, which is the only remaining class that loads a context. That list is not
a guess — `@SpringBootTest`, `@DataJpaTest` and `TestcontainersConfiguration` appear in exactly
those classes and nowhere else in `src/test`, so the 119 tests that survive the filter never touch a
container (the remaining ones are plain JUnit, `@WebMvcTest` with a mocked service, or bean-
validation and JSON-binding tests).

`clean verify` additionally runs `jacoco:report` and `jacoco:check`. Open
`target\site\jacoco\index.html` for the drill-down; the three thresholds (whole bundle ≥ 75 % line,
`service.dispatch.**` + `util` ≥ 85 %, `controller` ≥ 80 %) live in `pom.xml` and a miss fails the
build rather than posting a number nobody looks at. Measured values are in `README.md`.

Two things worth knowing before you chase a slow test run: `ApplicationEndpointsIT` and the other
container-backed classes pay for Testcontainers start-up once per context, which is why the wall
clock swings between ~40 s and ~2 min on the same code. And `DispatchPerformanceIT` logs a median
that is **inflated by the JaCoCo agent** whenever you run it through `verify` — for the honest number
append `"-Djacoco.skip=true"`.

Per-class counts and what each layer proves are in `README.md`.

## 7. Troubleshooting — the things that actually happened while building this

| Symptom | Cause and fix |
|---|---|
| `Could not find a valid Docker environment` at `DispatchApplicationTests.contextLoads`, with the rest of the suite green | Docker Desktop stopped mid-run (it happens). Restart it and re-run; it is an environment failure, not a code failure. Or use the no-Docker command from step 6. |
| `Validate failed: migration checksum mismatch` (Flyway refuses to start) | The schema was edited by hand, so Flyway no longer recognises it. This database is disposable: `docker compose down -v ; docker compose up -d` (`-v` deletes the volume — **all orders and vehicles go with it**). |
| The app refuses to start because a port is taken (`5433` or `8080`) | Another stack holds it: `Get-NetTCPConnection -LocalPort 5433 \| Select-Object -ExpandProperty OwningProcess \| ForEach-Object { Get-Process -Id $_ }`, then stop that process (or another `docker compose` project). |
| `cannot find symbol` on Lombok getters, or `No implementation was created for DispatchMapper` | Annotation processing is off in the IDE (or the Lombok plugin is missing). Enable it, or build from the command line where the compiler plugin config applies. |
| App starts but ignores `.env` values | Working directory is not the module root, or an OS environment variable / `-D` of the same name is overriding it. `.\mvnw.cmd spring-boot:run` from the repo root is the reliable route. |
| First `.\mvnw.cmd` invocation hangs or fails | The wrapper downloads Maven 3.10.1 on first use; it needs network once, then caches under `~/.m2/wrapper`. |
| `POST /api/dispatch/orders` returns `400 MALFORMED_JSON` for a payload that looks fine | Usually one of: a field name typo (unknown fields are rejected on purpose), `priority` not exactly `HIGH`/`MEDIUM`/`LOW`, or a JSON comment/trailing comma. The error body's `path` and `code` tell you which family; `fieldErrors` is empty precisely because binding failed. |
| `docker run` aborts with `ports are not available: exposing port TCP 127.0.0.1:8080 ... bind: An attempt was made to access a socket in a way forbidden by its access permissions` | Something on the host already owns 8080 — usually an app you left running (`Get-NetTCPConnection -LocalPort 8080 \| Select-Object -ExpandProperty OwningProcess \| ForEach-Object { Get-Process -Id $_ }`). Either stop it, or publish a different host port: `-p 127.0.0.1:18080:8080` and then use `http://127.0.0.1:18080/...`. |
| The container starts and then dies with `Connection to localhost refused` / Flyway cannot connect | `localhost` inside a container is the container, not your laptop. Pass `-e DB_URL="jdbc:postgresql://host.docker.internal:5433/dispatch"` and `--add-host=host.docker.internal:host-gateway` (the README's `docker run` line already has both). |

## 8. Where things live

| Path | What |
|---|---|
| `README.md` | Overview, algorithm, design decisions, measured performance |
| `API.md` | Full request/response/error contract with verbatim examples |
| `postman/dispatch-load-balancer.postman_collection.json` | Importable collection, assertions per request |
| `src/test/resources/sample/` | The four committed demo payloads (Delhi 30×5, Bangalore 2×2) |
| `src/main/java/com/bunny/dispatch/service/dispatch/handler/` | The assignment algorithm — no Spring imports in this package |
| `src/main/resources/db/migration/` | The one Flyway migration |
| `Dockerfile`, `.dockerignore` | Multi-stage image build (non-root, `/healthz` healthcheck) |
| `.github/workflows/ci.yml` | PRs into `deploy/production`: `verify` (suite + coverage gate), a Dockerfile build check, deliverable/gitignore guard, secret scan |
| `.github/workflows/deploy.yml`, `.github/scripts/check-migration-order.sh` | `deploy/production` pipeline: `verify` first, then the migration guard, then `build-and-push` (Docker Hub) and the Render deploy hook behind the `production` environment approval — **skips everything after `verify` until the Docker Hub repo, the Render *Existing Image* service, the secrets and `vars.DEPLOY_ENABLED=true` exist**. CI builds the image; Render only runs it (`README.md` § Branches and pipelines) |
| `Z-Docs/ADR-001-greedy-priority-assignment.md` | Why greedy-priority-then-nearest, and what was rejected |
| `Implementation_plan/`, `Docs/` | Local working notes and the company template references — **both are gitignored, so they are not in a clone and are not part of the deliverable** |
