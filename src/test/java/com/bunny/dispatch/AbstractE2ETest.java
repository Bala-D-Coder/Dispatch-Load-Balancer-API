package com.bunny.dispatch;

import com.bunny.dispatch.dto.response.DispatchPlanResponse;
import com.bunny.dispatch.service.dispatch.DispatchService;
import com.bunny.dispatch.support.SampleData;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real HTTP surface (MockMvc through the full DispatcherServlet chain) + real Postgres
 * (Testcontainers, Flyway-migrated from empty) + real service/mapper/algorithm. Parts 1–6
 * proved each layer in isolation; the subclasses prove the stack.
 *
 * <p>Deliberately NOT {@code @Transactional}: the endpoints commit, and the point of these
 * tests is that the NEXT request sees the committed rows (fresh recompute, D6). Isolation
 * comes from clearing data before each test instead. Subclasses must add no class-level
 * annotations so they all share one cached Spring context — and one container.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class AbstractE2ETest {

	protected static final String ORDERS_URL = "/api/dispatch/orders";
	protected static final String VEHICLES_URL = "/api/dispatch/vehicles";
	protected static final String PLAN_URL = "/api/dispatch/plan";
	protected static final String DATA_URL = "/api/dispatch/data";

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected DispatchService service;

	@Autowired
	protected ObjectMapper objectMapper;

	@BeforeEach
	void resetData() {
		service.clearData();
	}

	protected String postForBody(String url, String json) throws Exception {
		return mockMvc.perform(MockMvcRequestBuilders.post(url)
						.contentType("application/json")
						.content(json))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	protected void postSample(String url, String json) throws Exception {
		postForBody(url, json);
	}

	protected void postDelhiBatches() throws Exception {
		postSample(VEHICLES_URL, SampleData.vehiclesJson("delhi-vehicles.json"));
		postSample(ORDERS_URL, SampleData.ordersJson("delhi-orders.json"));
	}

	protected MvcResult getPlan() throws Exception {
		return mockMvc.perform(MockMvcRequestBuilders.get(PLAN_URL))
				.andExpect(status().isOk())
				.andReturn();
	}

	protected String planBody() throws Exception {
		String body = getPlan().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).isNotBlank();
		return body;
	}

	protected DispatchPlanResponse plan() throws Exception {
		return readJson(planBody(), DispatchPlanResponse.class);
	}

	protected <T> T readJson(String body, Class<T> type) {
		return objectMapper.readValue(body, type);
	}

}
