package com.bunny.dispatch.controller;

import com.bunny.dispatch.dto.request.OrdersBatchRequest;
import com.bunny.dispatch.dto.request.VehiclesBatchRequest;
import com.bunny.dispatch.dto.response.AckResponse;
import com.bunny.dispatch.dto.response.ClearDataResponse;
import com.bunny.dispatch.dto.response.DispatchPlanResponse;
import com.bunny.dispatch.service.dispatch.DispatchService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP surface over {@link DispatchService} — deliberately no logic and no
 * {@code ResponseEntity} wrapping: success is always 200 with the DTO body, and every
 * non-2xx is produced in exactly one place ({@code GlobalExceptionHandler}).
 */
@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

	private final DispatchService dispatchService;

	public DispatchController(DispatchService dispatchService) {
		this.dispatchService = dispatchService;
	}

	/** 200 ack; 400 VALIDATION_FAILED (E1) or DUPLICATE_IN_BATCH (E2); 400/415 transport (E4/E5). */
	@Operation(summary = "Ingest delivery orders (upsert by orderId)", description = """
			Validates every field, rejects a duplicate orderId inside the same batch with 400, \
			and upserts by orderId across requests. The ack reports received/inserted/updated.
			""")
	@PostMapping("/orders")
	public AckResponse ingestOrders(@Valid @RequestBody OrdersBatchRequest batch) {
		return dispatchService.ingestOrders(batch);
	}

	/** Same failure surface as {@link #ingestOrders}. */
	@Operation(summary = "Ingest fleet vehicles (upsert by vehicleId)", description = """
			Same semantics as /orders against vehicleId and capacity.
			""")
	@PostMapping("/vehicles")
	public AckResponse ingestVehicles(@Valid @RequestBody VehiclesBatchRequest batch) {
		return dispatchService.ingestVehicles(batch);
	}

	/** 200 fresh plan (no cache, D6); 404 NO_DISPATCH_DATA (E3) when both tables are empty. */
	@Operation(summary = "Build the dispatch plan from the current database state", description = """
			Recomputed on every call - nothing is cached. totalDistance is a round trip \
			(start -> stops -> start) emitted both as a spec-format string and as an exact \
			totalDistanceKm plus legs[]. 404 only when BOTH tables are empty; with vehicles \
			missing, orders come back unassigned with reason NO_VEHICLES_AVAILABLE.
			""")
	@GetMapping("/plan")
	public DispatchPlanResponse plan() {
		return dispatchService.buildPlan();
	}

	/** Additive reset endpoint (D9) — wipes orders and vehicles for clean demo runs. */
	@Operation(summary = "Delete all orders and vehicles (additive demo helper)", description = """
			Not part of the original specification - it exists so a Postman run can be \
			repeated from a known-empty state. Afterwards GET /plan returns 404 NO_DISPATCH_DATA.
			""")
	@DeleteMapping("/data")
	public ClearDataResponse clearData() {
		return dispatchService.clearData();
	}

}
