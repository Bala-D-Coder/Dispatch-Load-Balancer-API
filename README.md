# Dispatch Load Balancer API

A Spring Boot service that allocates delivery orders to a capacity-limited fleet and returns a
dispatch plan. It is a REST-only backend — no UI, no cache — designed to be driven straight from
Postman or the shell.

## How a plan is built

- Orders are assigned **HIGH priority first**.
- Each order goes to the **nearest feasible vehicle**, measured by Haversine distance from that
  vehicle's current route tip.
- Every vehicle's route is then re-ordered by **nearest-neighbour** and reported as a **round trip**,
  with the leg-by-leg arithmetic exposed so the distance can be checked by hand.
- A vehicle's load **never exceeds its capacity**.
- Whatever cannot be placed is returned in `unassignedOrders` with a **machine-readable reason**
  instead of silently disappearing.

## The interesting property

The sample fleet is deliberately smaller than the sample demand — **645 kg of orders against 500 kg
of vehicles** — so a "perfect" plan is impossible by design. The point of the exercise is not to
route everything, but to be correct about *what it refuses to carry and why*.

## Tech stack

Spring Boot 4 · Java 21 · PostgreSQL · Flyway migrations.

## API surface

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/dispatch/vehicles` | Ingest fleet vehicles (upsert by `vehicleId`) |
| `POST` | `/api/dispatch/orders` | Ingest delivery orders (upsert by `orderId`) |
| `GET`  | `/api/dispatch/plan` | Rebuild the dispatch plan from the current database state |
| `DELETE` | `/api/dispatch/data` | Clear every order and vehicle |

A machine-readable OpenAPI contract is served at `/v3/api-docs` and browsable at `/swagger-ui.html`;
`/healthz` reports service health.

## Example

Loading the bundled Delhi sample and requesting a plan yields, for example:

- **23 orders assigned**, 495 kg carried, `"171 km"` total distance;
- **7 LOW-priority orders** (150 kg) returned in `unassignedOrders` with reason
  `NO_VEHICLE_WITH_SUFFICIENT_REMAINING_CAPACITY`.

These are the exact numbers the end-to-end test asserts, so the documentation and the code cannot
drift apart without the build failing.

## Quality

167 tests, 95.9% coverage (465/485 branches), and a median plan time of about **63 ms** at
5,000 orders × 20 vehicles.

## Where the code lives

The implementation is on the `Impl` and `deploy/production` branches. This `main` branch carries the
project overview.
