package com.bunny.dispatch.controller;

import com.bunny.dispatch.dto.request.OrdersBatchRequest;
import com.bunny.dispatch.dto.response.AckResponse;
import com.bunny.dispatch.dto.response.AssignedOrderDto;
import com.bunny.dispatch.dto.response.ClearDataResponse;
import com.bunny.dispatch.dto.response.DispatchPlanResponse;
import com.bunny.dispatch.dto.response.PlanTotalsDto;
import com.bunny.dispatch.dto.response.RouteLegDto;
import com.bunny.dispatch.dto.response.UnassignedOrderDto;
import com.bunny.dispatch.dto.response.VehiclePlanDto;
import com.bunny.dispatch.entity.common.Priority;
import com.bunny.dispatch.entity.common.UnassignedReason;
import com.bunny.dispatch.exception.DuplicateInBatchException;
import com.bunny.dispatch.exception.NoDispatchDataException;
import com.bunny.dispatch.service.dispatch.DispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One test per error-matrix row (E1-E7 + happy paths), slice-only: the real advice runs,
 * the service is mocked so every exception path is reachable without Docker.
 */
@WebMvcTest(DispatchController.class)
class DispatchControllerTest {

	private static final String VALID_ORDER_JSON = """
			{"orders":[{"orderId":"ORD001","latitude":12.9716,"longitude":77.5946,
			 "address":"MG Road, Bangalore, Karnataka, India","packageWeight":10,"priority":"HIGH"}]}""";

	private static final String VALID_VEHICLE_JSON = """
			{"vehicles":[{"vehicleId":"VEH001","capacity":100,"currentLatitude":12.9716,
			 "currentLongitude":77.6413,"currentAddress":"Indiranagar, Bangalore, Karnataka, India"}]}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DispatchService dispatchService;

	/** Every error response must carry the same 7 keys — the client contract. */
	private org.springframework.test.web.servlet.ResultActions error(org.springframework.test.web.servlet.ResultActions actions,
			int status, String code) throws Exception {
		return actions
				.andExpect(status().is(status))
				.andExpect(jsonPath("$.timestamp").isString())
				.andExpect(jsonPath("$.status").value(status))
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.path").isString())
				.andExpect(jsonPath("$.fieldErrors").isArray());
	}

	// ---------- happy paths ----------

	@Test
	void postOrders_acceptedAckIsVerbatimSpecString() throws Exception {
		when(dispatchService.ingestOrders(any(OrdersBatchRequest.class)))
				.thenReturn(AckResponse.orders(1, 1, 0));

		mockMvc.perform(post("/api/dispatch/orders")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_ORDER_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Delivery orders accepted."))
				.andExpect(jsonPath("$.status").value("success"))
				.andExpect(jsonPath("$.received").value(1))
				.andExpect(jsonPath("$.inserted").value(1))
				.andExpect(jsonPath("$.updated").value(0));
	}

	@Test
	void postVehicles_acceptedAckIsVerbatimSpecString() throws Exception {
		when(dispatchService.ingestVehicles(any())).thenReturn(AckResponse.vehicles(1, 1, 0));

		mockMvc.perform(post("/api/dispatch/vehicles")
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_VEHICLE_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Vehicle details accepted."))
				.andExpect(jsonPath("$.status").value("success"));
	}

	@Test
	void getPlan_responseShapeMatchesSpec_plusAdditiveFields() throws Exception {
		// Bangalore fixture from Part 5 measured goldens (round trip 10.120580 km)
		VehiclePlanDto veh = new VehiclePlanDto("VEH001", "Indiranagar, Bangalore, Karnataka, India",
				10, "10 km", 10.120580,
				List.of(new RouteLegDto("Indiranagar, Bangalore, Karnataka, India",
								"MG Road, Bangalore, Karnataka, India", 5.060290),
						new RouteLegDto("MG Road, Bangalore, Karnataka, India",
								"Indiranagar, Bangalore, Karnataka, India", 5.060290)),
				List.of(new AssignedOrderDto("ORD001", 12.9716, 77.5946,
						"MG Road, Bangalore, Karnataka, India", 10, Priority.HIGH)));
		when(dispatchService.buildPlan()).thenReturn(new DispatchPlanResponse(
				List.of(veh),
				List.of(UnassignedOrderDto.of("ORD002", 20, Priority.LOW,
						UnassignedReason.EXCEEDS_ALL_VEHICLE_CAPACITIES)),
				new PlanTotalsDto(1, 1, 10, 10.120580, "10 km")));

		mockMvc.perform(get("/api/dispatch/plan"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dispatchPlan[0].vehicleId").value("VEH001"))
				.andExpect(jsonPath("$.dispatchPlan[0].totalLoad").value(10))
				.andExpect(jsonPath("$.dispatchPlan[0].totalDistance").value("10 km"))
				.andExpect(jsonPath("$.dispatchPlan[0].totalDistanceKm").value(10.120580))
				.andExpect(jsonPath("$.dispatchPlan[0].legs.length()").value(2))
				.andExpect(jsonPath("$.dispatchPlan[0].assignedOrders[0].orderId").value("ORD001"))
				.andExpect(jsonPath("$.dispatchPlan[0].assignedOrders[0].latitude").value(12.9716))
				.andExpect(jsonPath("$.dispatchPlan[0].assignedOrders[0].address")
						.value("MG Road, Bangalore, Karnataka, India"))
				.andExpect(jsonPath("$.dispatchPlan[0].assignedOrders[0].packageWeight").value(10))
				.andExpect(jsonPath("$.dispatchPlan[0].assignedOrders[0].priority").value("HIGH"))
				.andExpect(jsonPath("$.unassignedOrders[0].reason").value("EXCEEDS_ALL_VEHICLE_CAPACITIES"))
				.andExpect(jsonPath("$.unassignedOrders[0].reasonMessage").isString())
				.andExpect(jsonPath("$.totals.assignedOrderCount").value(1))
				.andExpect(jsonPath("$.totals.unassignedOrderCount").value(1));
	}

	@Test
	void deleteData_returnsClearAck() throws Exception {
		when(dispatchService.clearData()).thenReturn(ClearDataResponse.of(2, 1));

		mockMvc.perform(delete("/api/dispatch/data"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Dispatch data cleared."))
				.andExpect(jsonPath("$.status").value("success"))
				.andExpect(jsonPath("$.deletedOrders").value(2))
				.andExpect(jsonPath("$.deletedVehicles").value(1));
	}

	// ---------- E1 validation ----------

	@Test
	void latitudeOutOfRange_400_withFieldError() throws Exception {
		String body = VALID_ORDER_JSON.replace("12.9716,\"longitude\"", "91.0,\"longitude\"");

		error(mockMvc.perform(post("/api/dispatch/orders")
						.contentType(MediaType.APPLICATION_JSON).content(body)),
				400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.fieldErrors[0].field").value("orders[0].latitude"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("latitude must be between -90 and 90"));
		verify(dispatchService, never()).ingestOrders(any());
	}

	@Test
	void zeroWeight_400_withFieldError() throws Exception {
		String body = VALID_ORDER_JSON.replace("\"packageWeight\":10", "\"packageWeight\":0");

		error(mockMvc.perform(post("/api/dispatch/orders")
						.contentType(MediaType.APPLICATION_JSON).content(body)),
				400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.fieldErrors[0].field").value("orders[0].packageWeight"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("packageWeight must be greater than 0"));
	}

	@Test
	void blankOrderId_400_withFieldError() throws Exception {
		String body = VALID_ORDER_JSON.replace("\"orderId\":\"ORD001\"", "\"orderId\":\"  \"");

		error(mockMvc.perform(post("/api/dispatch/orders")
						.contentType(MediaType.APPLICATION_JSON).content(body)),
				400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.fieldErrors[0].field").value("orders[0].orderId"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("orderId must not be blank"));
	}

	@Test
	void emptyOrdersArray_400_atLeastOneRequired() throws Exception {
		error(mockMvc.perform(post("/api/dispatch/orders")
						.contentType(MediaType.APPLICATION_JSON).content("{\"orders\":[]}")),
				400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.fieldErrors[0].field").value("orders"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("At least one order is required"));
	}

	@Test
	void capacityOverMax_400_withFieldError() throws Exception {
		String body = VALID_VEHICLE_JSON.replace("\"capacity\":100", "\"capacity\":100001");

		error(mockMvc.perform(post("/api/dispatch/vehicles")
						.contentType(MediaType.APPLICATION_JSON).content(body)),
				400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.fieldErrors[0].field").value("vehicles[0].capacity"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("capacity must not exceed 100000 kg"));
	}

	// ---------- E2/E3 service exceptions ----------

	@Test
	void duplicateInBatch_400_namesTheKey() throws Exception {
		when(dispatchService.ingestOrders(any()))
				.thenThrow(new DuplicateInBatchException("Duplicate orderId in request: ORD005"));

		error(mockMvc.perform(post("/api/dispatch/orders")
						.contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER_JSON)),
				400, "DUPLICATE_IN_BATCH")
				.andExpect(jsonPath("$.message").value("Duplicate orderId in request: ORD005"));
	}

	@Test
	void planWithoutData_404() throws Exception {
		when(dispatchService.buildPlan())
				.thenThrow(new NoDispatchDataException("No dispatch data available. Post orders and vehicles first."));

		error(mockMvc.perform(get("/api/dispatch/plan")), 404, "NO_DISPATCH_DATA")
				.andExpect(jsonPath("$.message").value("No dispatch data available. Post orders and vehicles first."));
	}

	@Test
	void planWithOrdersOnly_is200Not404() throws Exception {
		when(dispatchService.buildPlan()).thenReturn(new DispatchPlanResponse(
				List.of(),
				List.of(UnassignedOrderDto.of("ORD001", 10, Priority.HIGH, UnassignedReason.NO_VEHICLES_AVAILABLE)),
				new PlanTotalsDto(0, 1, 0, 0.0, "0 km")));

		mockMvc.perform(get("/api/dispatch/plan"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dispatchPlan").isArray())
				.andExpect(jsonPath("$.dispatchPlan.length()").value(0))
				.andExpect(jsonPath("$.unassignedOrders[0].reason").value("NO_VEHICLES_AVAILABLE"));
	}

	// ---------- E4-E6 transport ----------

	@Test
	void malformedJson_400_doesNotEchoPayload() throws Exception {
		error(mockMvc.perform(post("/api/dispatch/orders")
						.contentType(MediaType.APPLICATION_JSON).content("{\"orders\": [{oops")),
				400, "MALFORMED_JSON")
				.andExpect(jsonPath("$.message").value("Malformed or unreadable JSON request body"));
	}

	@Test
	void unknownField_400_doesNotEchoFieldValue() throws Exception {
		String body = VALID_ORDER_JSON.replace("\"priority\":\"HIGH\"", "\"priority\":\"HIGH\",\"foo\":\"bar-secret\"");

		error(mockMvc.perform(post("/api/dispatch/orders")
						.contentType(MediaType.APPLICATION_JSON).content(body)),
				400, "MALFORMED_JSON");
	}

	@Test
	void invalidPriorityEnum_pinnedBehaviour() throws Exception {
		// PINNED by observed run 2026-11-20: an invalid enum value fails in Jackson BEFORE bean
		// validation, so it surfaces as MALFORMED_JSON (fixed message), not as a field error.
		// Clients distinguish it from a syntax error only via the request itself — documented in README.
		String body = VALID_ORDER_JSON.replace("\"priority\":\"HIGH\"", "\"priority\":\"urgent\"");

		error(mockMvc.perform(post("/api/dispatch/orders")
						.contentType(MediaType.APPLICATION_JSON).content(body)),
				400, "MALFORMED_JSON")
				.andExpect(result -> org.assertj.core.api.Assertions
						.assertThat(result.getResponse().getContentAsString())
						.doesNotContain("urgent"));
	}

	@Test
	void wrongContentType_415() throws Exception {
		error(mockMvc.perform(post("/api/dispatch/orders")
						.contentType(MediaType.TEXT_PLAIN).content("hello")),
				415, "UNSUPPORTED_MEDIA_TYPE")
				.andExpect(jsonPath("$.message").value("Content-Type must be application/json"));
	}

	@Test
	void deleteOnOrdersPath_405() throws Exception {
		error(mockMvc.perform(delete("/api/dispatch/orders")), 405, "METHOD_NOT_ALLOWED");
	}

	@Test
	void unknownPathUnderApi_404_not500() throws Exception {
		error(mockMvc.perform(get("/api/dispatch/unknown")), 404, "ENDPOINT_NOT_FOUND");
	}

	// ---------- E7 catch-all ----------

	@Test
	void unexpectedServiceFailure_500_neverLeaksInternalMessage() throws Exception {
		when(dispatchService.buildPlan()).thenThrow(new RuntimeException("secret-sql-detail"));

		error(mockMvc.perform(get("/api/dispatch/plan")), 500, "INTERNAL_ERROR")
				.andExpect(jsonPath("$.message").value("Internal server error"))
				.andExpect(result -> org.assertj.core.api.Assertions
						.assertThat(result.getResponse().getContentAsString())
						.doesNotContain("secret-sql-detail"));
	}

}
