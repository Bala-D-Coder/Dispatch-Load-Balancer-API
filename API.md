# Dispatch Load Balancer — API reference

REST only, no UI. Every example body and every error message in this file was copied from a real
response produced by the running application (or from `src/test/resources/sample/*`), not written
from imagination. Base URL in this document: `http://localhost:8080`.

- **Content type:** `application/json` in both directions. Any other `Content-Type` on a POST is
  refused with `415` before the body is read.
- **Encoding:** request field names are exact and case-sensitive; unknown fields are a `400`, not a
  silent ignore (`spring.jackson.deserialization.fail-on-unknown-properties: true`).
- **Auth:** none. This is a deliberate choice for a Postman-testable exercise — see
  [What is not here](README.md#what-i-did-not-do-and-why).
- **Machine-readable contract:** `GET /v3/api-docs` (OpenAPI 3, importable into Postman directly),
  browsable at `GET /swagger-ui.html`.

## Endpoints

| # | Method | Path | Success | Purpose |
|---|---|---|---|---|
| 1 | `POST` | `/api/dispatch/orders` | `200` + ack | Ingest delivery orders (upsert by `orderId`) |
| 2 | `POST` | `/api/dispatch/vehicles` | `200` + ack | Ingest fleet vehicles (upsert by `vehicleId`) |
| 3 | `GET` | `/api/dispatch/plan` | `200` + plan | Rebuild the dispatch plan from the current database state |
| 4 | `DELETE` | `/api/dispatch/data` | `200` + counts | **Additive** helper: delete every order and vehicle |

Operations endpoints, not part of the API surface: `GET /healthz` (Actuator, details hidden).

Success is always HTTP `200` with the DTO as the body — there is no `201`/`204` variation to
handle. Every non-2xx is produced in exactly one class (`GlobalExceptionHandler`) and always uses
the [error envelope](#the-error-envelope) below.

---

## 1. `POST /api/dispatch/orders`

### Request

The shape, using the first element of the committed sample
(`src/test/resources/sample/bangalore-orders.json`):

```json
{
  "orders": [
    {
      "orderId": "ORD001",
      "latitude": 12.9716,
      "longitude": 77.5946,
      "address": "MG Road, Bangalore, Karnataka, India",
      "packageWeight": 10,
      "priority": "HIGH"
    }
  ]
}
```

| Field | Type | Rule | Failure message (verbatim) |
|---|---|---|---|
| `orders` | array | at least one element | `At least one order is required` |
| `orderId` | string | not blank, ≤ 64 characters | `orderId must not be blank` / `orderId must not exceed 64 characters` |
| `latitude` | number | −90 … 90 | `latitude must be between -90 and 90` |
| `longitude` | number | −180 … 180 | `longitude must be between -180 and 180` |
| `address` | string | not blank, ≤ 512 characters | `address must not be blank` / `address must not exceed 512 characters` |
| `packageWeight` | integer (kg) | > 0, ≤ 10000 | `packageWeight must be greater than 0` / `packageWeight must not exceed 10000 kg` |
| `priority` | enum | exactly `HIGH`, `MEDIUM` or `LOW` | `priority is required and must be HIGH, MEDIUM or LOW` |

`latitude`/`longitude`/`packageWeight`/`priority` are required; the "is required" variants of those
messages (`latitude is required`, …) appear when the field is absent entirely. The two length caps
are the column widths (`VARCHAR(64)`, `VARCHAR(512)`) — Hibernate's `validate` mode checks that a
column exists, never how wide it is, so the width is asserted here instead. Without it a
513-character address reaches the driver and answers `500 INTERNAL_ERROR` for what is a client
mistake (`DispatchFlowE2EIT.overLongStringsFailValidationAtTheBoundaryAndNeverReachTheDatabase`).

### Response — `200`

```json
{"message": "Delivery orders accepted.", "status": "success", "received": 2, "inserted": 2, "updated": 0}
```

`message` and `status` are the specification's literal strings; `received`/`inserted`/`updated` are
additive (and always add up: `received == inserted + updated`).

### Semantics that are easy to guess wrong

- **Upsert across requests, 400 within one request.** Re-posting `ORD001` with a new weight updates
  the row (`updated: 1`). Listing `ORD001` twice *inside one batch* is ambiguous intent and returns
  `400 DUPLICATE_IN_BATCH` with nothing written.
- **One bad element fails the whole batch.** Validation runs before anything is saved, so there is
  no partial ingestion to reason about.
- **`priority` is case-sensitive.** `"urgent"` or `"high"` is rejected at JSON binding, therefore it
  comes back as `MALFORMED_JSON` with an empty `fieldErrors`, *not* as a field error.

---

## 2. `POST /api/dispatch/vehicles`

### Request

Same convention — the first element of `src/test/resources/sample/bangalore-vehicles.json`:

```json
{
  "vehicles": [
    {
      "vehicleId": "VEH001",
      "capacity": 100,
      "currentLatitude": 12.9716,
      "currentLongitude": 77.6413,
      "currentAddress": "Indiranagar, Bangalore, Karnataka, India"
    }
  ]
}
```

| Field | Type | Rule | Failure message (verbatim) |
|---|---|---|---|
| `vehicles` | array | at least one element | `At least one vehicle is required` |
| `vehicleId` | string | not blank, ≤ 64 characters | `vehicleId must not be blank` / `vehicleId must not exceed 64 characters` |
| `capacity` | integer (kg) | > 0, ≤ 100000 | `capacity must be greater than 0` / `capacity must not exceed 100000 kg` |
| `currentLatitude` | number | −90 … 90 | `currentLatitude must be between -90 and 90` |
| `currentLongitude` | number | −180 … 180 | `currentLongitude must be between -180 and 180` |
| `currentAddress` | string | not blank, ≤ 512 characters | `currentAddress must not be blank` / `currentAddress must not exceed 512 characters` |

### Response — `200`

```json
{"message": "Vehicle details accepted.", "status": "success", "received": 2, "inserted": 2, "updated": 0}
```

Same upsert / duplicate / all-or-nothing semantics as `/orders`, keyed on `vehicleId`.

---

## 3. `GET /api/dispatch/plan`

Takes no parameters and has no body. The plan is **recomputed from the database on every call** —
there is no cache and no plan is persisted, so two consecutive calls on unchanged data return
byte-identical JSON (asserted by `DispatchPlanE2EIT`).

### Response — `200` (the spec's own 2-order sample, real output)

```json
{
  "dispatchPlan": [
    {
      "vehicleId": "VEH001",
      "currentAddress": "Indiranagar, Bangalore, Karnataka, India",
      "totalLoad": 10,
      "totalDistance": "10 km",
      "totalDistanceKm": 10.120580497298127,
      "legs": [
        {
          "fromAddress": "Indiranagar, Bangalore, Karnataka, India",
          "toAddress": "MG Road, Bangalore, Karnataka, India",
          "distanceKm": 5.060290248649063
        },
        {
          "fromAddress": "MG Road, Bangalore, Karnataka, India",
          "toAddress": "Indiranagar, Bangalore, Karnataka, India",
          "distanceKm": 5.060290248649063
        }
      ],
      "assignedOrders": [
        {
          "orderId": "ORD001",
          "latitude": 12.9716,
          "longitude": 77.5946,
          "address": "MG Road, Bangalore, Karnataka, India",
          "packageWeight": 10,
          "priority": "HIGH"
        }
      ]
    },
    {
      "vehicleId": "VEH002",
      "currentAddress": "T Nagar, Chennai, Tamil Nadu, India",
      "totalLoad": 20,
      "totalDistance": "8 km",
      "totalDistanceKm": 7.936632638843489,
      "legs": [
        {
          "fromAddress": "T Nagar, Chennai, Tamil Nadu, India",
          "toAddress": "Anna Salai, Chennai, Tamil Nadu, India",
          "distanceKm": 3.9683163194217443
        },
        {
          "fromAddress": "Anna Salai, Chennai, Tamil Nadu, India",
          "toAddress": "T Nagar, Chennai, Tamil Nadu, India",
          "distanceKm": 3.9683163194217443
        }
      ],
      "assignedOrders": [
        {
          "orderId": "ORD002",
          "latitude": 13.0827,
          "longitude": 80.2707,
          "address": "Anna Salai, Chennai, Tamil Nadu, India",
          "packageWeight": 20,
          "priority": "MEDIUM"
        }
      ]
    }
  ],
  "unassignedOrders": [],
  "totals": {
    "assignedOrderCount": 2,
    "unassignedOrderCount": 0,
    "totalLoadKg": 30,
    "totalDistanceKm": 18.057213136141616,
    "totalDistance": "18 km"
  }
}
```

### Fields

| Field | In spec | Notes |
|---|---|---|
| `dispatchPlan[]` | yes | Only vehicles that got at least one stop. An idle vehicle adds noise, and the spec's own example lists only the vehicles that drive. |
| `dispatchPlan[].vehicleId`, `totalLoad`, `totalDistance`, `assignedOrders` | yes | `totalLoad` is the sum of that vehicle's `packageWeight`s and is never above the vehicle's `capacity`. |
| `dispatchPlan[].currentAddress` | additive | The round trip's start **and** end, so `legs` can be checked without another request. |
| `dispatchPlan[].totalDistanceKm` | additive | The unrounded figure behind `totalDistance`. |
| `dispatchPlan[].legs[]` | additive | The auditable chain: `legs[0].fromAddress == currentAddress`, each stop in `assignedOrders` appears as one `toAddress` in order, and the final `toAddress` is `currentAddress` again. `legs.size() == assignedOrders.size() + 1`. |
| `unassignedOrders[]` | additive | Every order that could not be placed, with a machine-readable `reason` and a human `reasonMessage`. |
| `totals` | additive | `assignedOrderCount + unassignedOrderCount` always equals the number of orders in the database — nothing silently disappears. |

### Distance semantics — read this before comparing against the spec's example

`totalDistance` is a **round trip**: vehicle start → every stop in visiting order → start again.
The specification's sample response shows `"5 km"` and `"6 km"` for these same two payloads; those
are illustrative one-way numbers. The measured output above is `"10 km"` and `"8 km"` — `2 ×` the
one-way figure (5.060290 km out, 5.060290 km back). `sum(legs[].distanceKm) == totalDistanceKm`,
which is the fastest way to verify a number by hand.

Rounding rule: `totalDistance` is `Math.round(totalDistanceKm)` plus `" km"`, matching the spec's
`"5 km"` string format. `legs[].distanceKm` and `totalDistanceKm` are exact doubles; equality of
doubles across calls is stable because the same inputs produce the same operation order.

**Visiting order is not priority order.** Priority decides who competes for capacity first
(`OrderSorter`); once a vehicle owns its stops, `RoutePlanner` re-orders them by nearest neighbour
from the vehicle's start, because that is the driving route. A `HIGH` stop 286 km away can therefore
appear *after* a `MEDIUM` stop 4 km away in `assignedOrders[]` without anything being wrong: priority
governs who gets capacity, not the order the vehicle drives. That exact case is pinned by
`DispatchServiceUpsertIT.reingestVehiclePreservesCreatedAtAndCapacityFeedsNextPlan`.

### `404` versus `200` on an empty database

| Database state | Result |
|---|---|
| No orders **and** no vehicles | `404 NO_DISPATCH_DATA` |
| Vehicles only | `200` with `dispatchPlan: []`, `unassignedOrders: []`, all totals `0` |
| Orders only | `200` with `dispatchPlan: []` and every order in `unassignedOrders` as `NO_VEHICLES_AVAILABLE` |

Only the genuinely-unknown state (`nothing has been posted yet`) is a 404. "There is no fleet" is
information the caller asked for, so it is a 200 with an explanation per order.

### `unassignedOrders[].reason` values

| `reason` | `reasonMessage` | When |
|---|---|---|
| `EXCEEDS_ALL_VEHICLE_CAPACITIES` | `Order weight exceeds every vehicle's total capacity` | The order could never fit any vehicle, even empty — no retry will help. |
| `NO_VEHICLE_WITH_SUFFICIENT_REMAINING_CAPACITY` | `No vehicle has enough remaining capacity` | The fleet is full by the time this order is reached (it is always the lowest-priority, lightest orders that lose). |
| `NO_VEHICLES_AVAILABLE` | `No vehicles registered` | Orders were posted but no vehicle was. |

---

## 4. `DELETE /api/dispatch/data` (additive)

Not in the original specification. It exists so a Postman run or a demo can restart from a known
state instead of accumulating rows between submissions.

### Response — `200`

Immediately after the Delhi demo (30 orders, 5 vehicles) posted above:

```json
{"message": "Dispatch data cleared.", "status": "success", "deletedOrders": 30, "deletedVehicles": 5}
```

Both counts are the row counts *before* deletion, so `0 / 0` is a valid, non-error response — the
same call twice in a row clears 30/5 and then nothing. After this call, `GET /api/dispatch/plan`
returns `404`.

---

## The error envelope

Every non-2xx body has exactly these seven keys. `code` is the stable machine-readable field;
`message` is human text and may be reworded, so match on `code`.

Below is the real `400` produced by request 31 of the Postman collection, showing two of its six
`fieldErrors` entries. The array order is not contractual — match by `field`, not by position.

```json
{
  "timestamp": "2026-11-20T16:21:21.053636900Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_FAILED",
  "message": "Validation failed for 6 field(s)",
  "path": "/api/dispatch/orders",
  "fieldErrors": [
    {"field": "orders[0].orderId", "rejectedValue": "", "message": "orderId must not be blank"},
    {"field": "orders[0].latitude", "rejectedValue": 91.0, "message": "latitude must be between -90 and 90"}
  ]
}
```

`fieldErrors` is `[]` for every error that is not a bean-validation failure, and `rejectedValue` is
`null` for a field that was simply absent. The `field` path is indexed (`orders[1].priority`) so a
caller can point at the offending element of a batch.

### Full error matrix

| Code | HTTP | `message` (verbatim) | Cause |
|---|---|---|---|
| `VALIDATION_FAILED` | `400` | `Validation failed for 6 field(s)` (count varies) | One or more fields broke a constraint; details in `fieldErrors`. |
| `DUPLICATE_IN_BATCH` | `400` | `Duplicate orderId in request: ORD001` (or `vehicleId`) | The same natural key twice in one batch. Nothing was written. |
| `MALFORMED_JSON` | `400` | `Malformed or unreadable JSON request body` | Unparseable JSON, an invalid enum value, an unknown field, or a type mismatch. Fixed text — the parser's own message (which can quote input back) never reaches the client. |
| `UNSUPPORTED_MEDIA_TYPE` | `415` | `Content-Type must be application/json` | POST with another content type. |
| `METHOD_NOT_ALLOWED` | `405` | `Method DELETE not supported for path /api/dispatch/orders` | A real path used with a method it does not serve. |
| `ENDPOINT_NOT_FOUND` | `404` | `No endpoint found for path /api/dispatch/nope` | Unmapped path — same envelope, not a framework HTML page. |
| `NO_DISPATCH_DATA` | `404` | `No dispatch data available. Post orders and vehicles first.` | `GET /plan` with both tables empty. |
| `INTERNAL_ERROR` | `500` | `Internal server error` | Backstop. The exception message is deliberately never echoed (it could contain SQL or driver internals); the stack trace goes to the application log. |

`MALFORMED_JSON` covers three cases a client might expect to be field errors — unknown fields, bad
enum values and type mismatches — because they all fail during JSON binding, before bean validation
has an object to inspect. That is why `fieldErrors` only ever carries well-typed-but-out-of-range
values.

---

## Driving it from a shell

PowerShell (your terminal), against a locally running instance:

```powershell
$base = "http://localhost:8080/api/dispatch"

Invoke-RestMethod -Method Delete -Uri "$base/data"

Invoke-RestMethod -Method Post -Uri "$base/vehicles" -ContentType "application/json" `
  -InFile "src\test\resources\sample\bangalore-vehicles.json"

Invoke-RestMethod -Method Post -Uri "$base/orders" -ContentType "application/json" `
  -InFile "src\test\resources\sample\bangalore-orders.json"

Invoke-RestMethod -Method Get -Uri "$base/plan" | ConvertTo-Json -Depth 6
```

The four committed payloads under `src/test/resources/sample/` are the same files the integration
tests and the Postman collection use. Delhi is the interesting one: 30 orders / 645 kg against a
500 kg fleet, which cannot be fully assigned by construction.

To see the failure shape without a second tool:

```powershell
Invoke-RestMethod -Method Post -Uri "$base/orders" -ContentType "application/json" `
  -Body '{"orders":[{"orderId":"","latitude":91,"longitude":77.2,"address":"x","packageWeight":0,"priority":"HIGH"}]}'
# -> 400, code VALIDATION_FAILED, fieldErrors for orderId / latitude / packageWeight
```

curl equivalent of the three happy-path calls:

```bash
curl -X DELETE http://localhost:8080/api/dispatch/data
curl -X POST http://localhost:8080/api/dispatch/vehicles -H "Content-Type: application/json" \
  --data @src/test/resources/sample/bangalore-vehicles.json
curl -X POST http://localhost:8080/api/dispatch/orders -H "Content-Type: application/json" \
  --data @src/test/resources/sample/bangalore-orders.json
curl http://localhost:8080/api/dispatch/plan
```

## What this API adds on top of the specification

| Addition | Why |
|---|---|
| `DELETE /api/dispatch/data` | Repeatable demos and Postman runs. |
| `totalDistanceKm`, `legs[]`, `currentAddress` on each vehicle | `totalDistance` is a rounded string, which is untestable and hard to audit; these make every kilometre checkable. |
| `unassignedOrders[]` with `reason` / `reasonMessage` | "Handle overcapacity and edge cases" needs an answer that is not just silence. |
| `totals` | Proves `assigned + unassigned == every order posted`. |
| `received` / `inserted` / `updated` on both acks | Makes upsert behaviour observable instead of assumed. |
| The 7-key error envelope with `code` | One shape for every failure, machine-matchable. |
| `/healthz`, `/v3/api-docs`, `/swagger-ui.html` | Operations and self-documentation. |

Everything the spec requires is present with the spec's own field names and its own success strings.
