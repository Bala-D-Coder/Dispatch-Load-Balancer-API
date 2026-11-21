package com.bunny.dispatch;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the three operational URLs the README promises, inside the real application context —
 * so a doc line cannot outlive its config. Shares {@link AbstractE2ETest}'s cached context
 * (no extra container) and its data clearing, which this class does not care about.
 */
class ApplicationEndpointsIT extends AbstractE2ETest {

	private static final List<String> ENDPOINT_PATHS = List.of("/api/dispatch/orders",
			"/api/dispatch/vehicles", "/api/dispatch/plan", "/api/dispatch/data");

	@Test
	void healthEndpointIsMappedAtHealthz() throws Exception {
		mockMvc.perform(get("/healthz"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void openApiContractCoversAllFourEndpoints() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		JsonNode paths = objectMapper.readTree(body).path("paths");
		assertThat(ENDPOINT_PATHS).allSatisfy(path ->
				assertThat(paths.has(path)).as("%s in /v3/api-docs", path).isTrue());
		assertThat(body).contains("Dispatch Load Balancer API");
	}

	/**
	 * The column widths are enforced in the request DTOs, so the only place a client can learn them
	 * without reading {@code API.md} is the served schema. Asserted rather than assumed: springdoc
	 * publishing {@code @Size} is a property of its model resolver, not of the annotation.
	 */
	@Test
	void publishedSchemaCarriesTheColumnWidthLimits() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		JsonNode schemas = objectMapper.readTree(body).path("components").path("schemas");

		JsonNode order = schemas.path("OrderRequest").path("properties");
		assertThat(order.path("address").path("maxLength").asInt()).isEqualTo(512);
		assertThat(order.path("orderId").path("maxLength").asInt()).isEqualTo(64);

		JsonNode vehicle = schemas.path("VehicleRequest").path("properties");
		assertThat(vehicle.path("currentAddress").path("maxLength").asInt()).isEqualTo(512);
		assertThat(vehicle.path("vehicleId").path("maxLength").asInt()).isEqualTo(64);
	}

	@Test
	void swaggerUiIsServed() throws Exception {
		// springdoc redirects the historic /swagger-ui.html to the versioned resource path
		mockMvc.perform(get("/swagger-ui.html"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isBetween(200, 399));
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isBetween(200, 399));
	}

}
