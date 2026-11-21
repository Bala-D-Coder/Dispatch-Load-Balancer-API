# ADR-001 — Greedy priority-then-nearest assignment (not an exact VRP solver)

- **Status:** Accepted (implemented, measured, test-pinned). Revised 2026-11-21 after the pre-commit
  review: the `DistanceCache` key was replaced on measured evidence — see the revision note in
  *Decision*.
- **Date:** 2026-11-20, revised 2026-11-21
- **Deciders:** balas (author) — recorded for review, open to correction
- **Related:** `README.md` (assumptions D1–D11), `API.md` (distance semantics),
  `src/main/java/com/bunny/dispatch/service/dispatch/handler/`, `util/DistanceUtils.java`

## Context

`GET /api/dispatch/plan` must assign every delivery order to a vehicle such that

1. a vehicle's total load never exceeds its `capacity`,
2. `HIGH` priority orders are assigned before lower priorities,
3. total travel distance is minimised.

Constraint 3, taken literally and jointly with 1, is a capacitated vehicle-routing problem
(CVRP) — NP-hard. The input sizes the brief names are a fleet "of vehicles" and batched
orders; the performance target I set and met is 5,000 orders × 20 vehicles. Two of the
three requirements are hard and cheap to satisfy exactly; the third is only satisfiable
approximately at that size, so the real decision is *which approximation, and how honest we
are about it*.

A second, less obvious driver: this code is being reviewed. A plan whose every leg can be
reproduced by hand from the source data is worth more than a marginally shorter plan nobody
can verify. Determinism is a requirement, not a nicety — two consecutive `GET /plan` calls
on unchanged data must return byte-identical JSON (that assertion is in
`DispatchPlanE2EIT`).

## Decision

A three-stage deterministic pipeline, no optimisation library:

1. **Sort** orders once — `priority rank`, then `packageWeight` descending, then `orderId`
   (`OrderSorter`). This is how "HIGH first" is honoured: priority is satisfied *by
   construction*, not as a tie-break afterthought.
2. **Assign** each order, in that order, to the feasible vehicle (`remaining >= packageWeight`)
   whose **current route tip** is nearest by Haversine (`GreedyAssignmentStrategy`). Ties break
   on larger remaining capacity, then lower `vehicleId`. Vehicles are iterated in `vehicleId`
   order so the scan itself is order-independent of the input batch.
3. **Route** each vehicle's stops with nearest-neighbour from its current position, then append
   the return leg (`RoutePlanner`).

Three deliberate asymmetries, all documented in `API.md`:

- *Selection* uses forward-only tip distance; the *reported* `totalDistance` is the full round
  trip. The heuristic optimises "which vehicle takes this next", the report answers "how far
  will this vehicle actually drive".
- Orders that fit no vehicle are **not** dropped or partially loaded: they come back in
  `unassignedOrders[]` with a machine-readable `reason` (`UnassignedReason`), and the response
  still returns `200`.
- Priority governs *who competes for capacity*, never *the order a vehicle drives*. Stage 1 fixes
  the competition sequence; stage 3 re-orders each vehicle's stops by nearest neighbour, so a
  `HIGH` stop can legitimately be visited after a `MEDIUM` one. Pinned by
  `DispatchServiceUpsertIT.reingestVehiclePreservesCreatedAtAndCapacityFeedsNextPlan`.

Distances are memoised in `DistanceCache` on the four coordinates' IEEE-754 bit patterns.

> **Revision, 2026-11-21 (pre-commit review).** This paragraph originally read "on 6-decimal
> coordinate keys formatted with `Locale.ROOT`". That key was measured and replaced. In one JVM, same
> 5,000 × 20 dataset, only the key differing: **25.5 ms with the formatted key, 9.7 ms with bit
> patterns**; on a batch built so every coordinate repeats, **865 ms → 20 ms**; with no cache at all,
> 10.8 ms / 44.9 ms. `String.format` costs ~1 µs and the Haversine it guarded costs ~50 ns, so the
> memoisation was a net loss everywhere — and it was lossy, because two points ~1 cm apart share a
> 6-decimal cell and the second query got the first pair's distance. Bit patterns cannot alias.
> Plan outcomes are identical across all key schemes tested (same route count, same unassigned count,
> same total distance to 9 dp), which is what makes the swap safe rather than merely faster.
> The reviewer's second point — `d(A,B)` equals `d(B,A)`, so ordered keys double the entries — is
> also true (78.7 % of distinct keys on the repeating batch had their reverse present) and also
> measured to be worth nothing once the key is cheap: canonicalising cost more than the reverse hits
> saved (11.2 ms vs 9.7 ms), so the cache stays ordered-pair and simpler.
>
> How those numbers were produced, so they can be re-derived: one JVM per key implementation, the
> variant class shadowing `DistanceCache` on the classpath while `GreedyAssignmentStrategy` and
> `RoutePlanner` stay the shipped ones, median of 7–9 `assign()` repeats after 3 warm-up runs, over
> three datasets — the `DispatchPerformanceIT` grid batch, an all-distinct-coordinate batch, and a
> batch with huge capacities so nothing is skipped and every coordinate repeats. The scripts were
> throwaway (`target/scan/`, deleted by the next `clean verify`); the parts that must stay true are
> pinned in the suite — `DistanceCacheTest.nearbyButDistinctPointsNeverShareAnEntry` (no aliasing)
> and `DistanceUtilsTest.worstMeasuredAntipodalInputIsFiniteAndCappedAtHalfTheCircumference` (the
> domain bound below). Full record: `Implementation_plan/IMPLEMENTATION_PLAN_PART11_REVIEW.md`.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Google OR-Tools / CP-SAT** | Would give genuinely better routes, but adds a native dependency and a solver process for a problem whose stated constraints are priority + capacity, not optimal mileage. It also makes the answer unexplainable in review: "the solver returned this" is not a traceable rule. |
| **Exact CVRP (branch-and-bound / MILP)** | Exponential. At 30 orders the search is already too large to run per HTTP request; the brief expects a synchronous endpoint. |
| **Clarke-Wright savings + 2-opt** | Improves *routing* given a fixed assignment; the brief's hard constraints are about *assignment*. Worse, 2-opt reorders stops so a leg is no longer checkable against the source data without reimplementing the improvement loop. |
| **Best-first / regret-based insertion** | Better plans than pure greedy on some instances, at higher constant cost and with tie-breaking subtleties that threaten determinism. Not worth it while the greedy result already satisfies every stated constraint. |
| **KD-tree / R-tree spatial index over vehicles** | `m` is a fleet size in the tens; the linear scan over feasible vehicles is not the bottleneck (measured whole-plan cost: ~0.06 s at 5,000 × 20). Indexing buys nothing until `m` is in the thousands. |
| **Assign to nearest vehicle by its *home* location instead of route tip** | Simpler and still deterministic, but it systematically overloads the vehicle nearest the depot with everything else too; tip-based distance reflects the load the vehicle is already carrying. |
| **Let capacity overflow with a warning** | Rejected outright: a plan that violates capacity is not a plan. |

## Consequences

**Positive**

- Every number in the response is reproducible from the input with a calculator and the source:
  the golden test asserts `legs[i].distanceKm` sums to `totalDistanceKm` and that the first and
  last leg endpoints are the vehicle's own `currentAddress`.
- Deterministic and idempotent under recompute — proven, not assumed (`DispatchPlanE2EIT`
  compares two consecutive responses byte-for-byte).
- `O(n log n)` sort + `O(n · m)` assignment + `O(kᵢ²)` per-vehicle routing. Predictable, and
  measured: **median 63 ms** for 5,000 orders × 20 vehicles across five uninstrumented JVMs
  (range 57–86 ms) against the 2,000 ms fence the test asserts. The JaCoCo agent adds roughly
  30 ms in exactly this hot loop (median 93 ms, `[112, 87, 93]`, and 153–331 ms on a cold
  machine), so the quoted figures are taken with `-Djacoco.skip=true`.
- Unassignable demand is surfaced, not hidden — the Delhi sample (645 kg demand against a
  500 kg fleet) returns 23 assigned / 495 kg and 7 orders / 150 kg in `unassignedOrders[]`,
  all with `NO_VEHICLE_WITH_SUFFICIENT_REMAINING_CAPACITY`.

**Negative / accepted**

- **No optimality claim.** The plan is feasible and locally sensible; it is not the shortest
  achievable fleet distance. `README.md` says this in as many words rather than implying more.
- Priority is honoured by *sequence*, not as a hard guarantee of assignment: a `HIGH` order
  heavier than every empty vehicle's capacity still ends up unassigned. Reason codes make that
  visible (`EXCEEDS_ALL_VEHICLE_CAPACITIES`).
- Greedy is order-sensitive: the sort key *is* part of the contract. Changing
  `priority → weight desc → orderId` to anything else changes every plan, so
  `OrderSorterTest` pins all three levels.
- Straight-line Haversine distance, per the brief, so tunnel/one-way/traffic effects are absent;
  `legs[]` exposes each hop so a consumer can substitute road distances later.
- No plan cache (D4): every `GET /plan` recomputes. At ~0.06 s that is cheaper than invalidation
  correctness bugs.

**Follow-ups if requirements grow**

- Demand exceeding fleet capacity becomes normal rather than a demo case → add a
  multi-trip/multi-vehicle split policy (currently one order = one vehicle, no splitting).
- Fleet sizes in the hundreds → revisit the spatial index, then consider a metaheuristic pass
  over the greedy result.
- Distance accuracy starts mattering → swap `DistanceUtils` for a road-distance provider behind
  the same interface; the algorithm is provider-agnostic.

## Evidence

| Claim | Where it is pinned |
|---|---|
| Priority-before-capacity ordering | `OrderSorterTest` (4), `GreedyAssignmentStrategyTest` (14) |
| Capacity never exceeded, per vehicle | `GreedyAssignmentStrategyTest`, `DispatchPlanE2EIT`, Postman folder 1 test script |
| Delhi golden: 23 / 495 kg, 7 / 150 kg, 170.557850 km | `DispatchPlanE2EIT` |
| Round-trip legs sum to the reported total | `RoutePlannerTest` (7), `DispatchPlanE2EIT` |
| Determinism across requests | `DispatchPlanE2EIT` (byte-identical recompute) |
| ~0.06 s median at 5,000 × 20 (uninstrumented) | `DispatchPerformanceIT` (`PERF buildPlan:` log line) |
| The memoised distance is the distance of *that* pair, never a neighbour's | `DistanceCacheTest.nearbyButDistinctPointsNeverShareAnEntry` |
| `asin` never leaves its domain for validated coordinates | `DistanceUtilsTest.worstMeasuredAntipodalInputIsFiniteAndCappedAtHalfTheCircumference` (96,571,199-case sweep, worst `a` = 1 + 1 ulp, 0 NaN) |
| Key change alters no plan | same route set / unassigned set / total distance (9 dp) under all five key schemes on three datasets — Part 11 record |
