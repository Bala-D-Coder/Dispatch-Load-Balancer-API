package com.bunny.dispatch.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bunny.dispatch.controller.DispatchController;
import com.bunny.dispatch.dto.response.AckResponse;
import com.bunny.dispatch.dto.response.ClearDataResponse;
import com.bunny.dispatch.dto.response.DispatchPlanResponse;
import com.bunny.dispatch.dto.response.PlanTotalsDto;
import com.bunny.dispatch.exception.GlobalExceptionHandler;
import com.bunny.dispatch.service.dispatch.DispatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The filter in isolation: standalone MockMvc over the real controller and advice, no Spring
 * context and no database, because what is pinned here is the log line rather than the plan.
 *
 * <p>{@code RequestLoggingIT} proves the same lines appear through the real servlet chain.
 */
class RequestLoggingFilterTest {

	private static final Pattern DURATION = Pattern.compile("durationMs=(\\d+) ");
	private static final Pattern UUID_SHAPE =
			Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private static final DispatchPlanResponse EMPTY_PLAN =
			new DispatchPlanResponse(List.of(), List.of(), new PlanTotalsDto(0, 0, 0, 0.0, "0 km"));

	private final DispatchService dispatchService = Mockito.mock(DispatchService.class);
	private final RequestLoggingFilter filter = new RequestLoggingFilter();

	private MockMvc mockMvc;
	private Logger filterLogger;
	private ListAppender<ILoggingEvent> appender;
	private Level originalLevel;

	@BeforeEach
	void attachAppender() {
		mockMvc = MockMvcBuilders.standaloneSetup(new DispatchController(dispatchService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.addFilters(filter)
				.build();
		filterLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
		originalLevel = filterLogger.getLevel();
		appender = new ListAppender<>();
		appender.start();
		filterLogger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		filterLogger.detachAppender(appender);
		filterLogger.setLevel(originalLevel);
		MDC.clear();
	}

	private List<String> infoLines() {
		return appender.list.stream()
				.filter(e -> e.getLevel() == Level.INFO)
				.map(ILoggingEvent::getFormattedMessage)
				.toList();
	}

	private static long durationOf(String line) {
		Matcher matcher = DURATION.matcher(line);
		assertThat(matcher.find()).as("durationMs field present in %s", line).isTrue();
		return Long.parseLong(matcher.group(1));
	}

	private String performPlan(String incomingRequestId) throws Exception {
		var builder = get("/api/dispatch/plan");
		if (incomingRequestId != null) {
			builder = builder.header(RequestLoggingFilter.REQUEST_ID_HEADER, incomingRequestId);
		}
		return mockMvc.perform(builder).andReturn().getResponse()
				.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
	}

	@Test
	void oneCallProducesExactlyOneInfoLineCarryingTheIdItReturned() throws Exception {
		when(dispatchService.buildPlan()).thenReturn(EMPTY_PLAN);
		String issued = performPlan(null);

		List<String> lines = infoLines();
		assertThat(lines).hasSize(1);
		assertThat(lines.get(0))
				.startsWith("GET /api/dispatch/plan status=200 durationMs=")
				.endsWith("requestId=" + issued);
		assertThat(durationOf(lines.get(0))).isGreaterThanOrEqualTo(0);
	}

	/** The id must reach the service layer, or the phase lines cannot be tied back to one call. */
	@Test
	void requestIdIsInMdcForTheWholeCallAndRemovedAfterwards() throws Exception {
		AtomicReference<String> seenInsideCall = new AtomicReference<>();
		when(dispatchService.buildPlan()).thenAnswer(invocation -> {
			seenInsideCall.set(MDC.get(RequestLoggingFilter.MDC_KEY));
			return EMPTY_PLAN;
		});

		String issued = performPlan("postman-run-7");

		assertThat(issued).isEqualTo("postman-run-7");
		assertThat(seenInsideCall.get()).isEqualTo("postman-run-7");
		assertThat(infoLines().get(0)).endsWith("requestId=postman-run-7");
		assertThat(MDC.get(RequestLoggingFilter.MDC_KEY)).isNull();
	}

	/**
	 * The header is untrusted input that is echoed into a response header and into the log line,
	 * so a value carrying CRLF must reach neither.
	 */
	@Test
	void hostileRequestIdHeaderIsDiscardedForAFreshId() throws Exception {
		when(dispatchService.buildPlan()).thenReturn(EMPTY_PLAN);
		String issued = performPlan("abc\r\nX-Injected: yes");

		assertThat(issued).matches(UUID_SHAPE.pattern());
		assertThat(infoLines()).hasSize(1);
		assertThat(infoLines().get(0)).doesNotContain("X-Injected").contains("requestId=" + issued);
	}

	@Test
	void errorResponsesAreStillLoggedOnceWithTheirStatus() throws Exception {
		when(dispatchService.buildPlan()).thenThrow(new RuntimeException("secret-detail"));

		mockMvc.perform(get("/api/dispatch/plan"));

		assertThat(infoLines()).hasSize(1);
		assertThat(infoLines().get(0)).contains("status=500");
	}

	@Test
	void eachOfTheFourEndpointsLogsOneLinePerCall() throws Exception {
		when(dispatchService.buildPlan()).thenReturn(EMPTY_PLAN);
		when(dispatchService.clearData()).thenReturn(ClearDataResponse.of(0, 0));
		when(dispatchService.ingestOrders(any())).thenReturn(AckResponse.orders(1, 1, 0));
		when(dispatchService.ingestVehicles(any())).thenReturn(AckResponse.vehicles(1, 1, 0));

		mockMvc.perform(get("/api/dispatch/plan"));
		mockMvc.perform(delete("/api/dispatch/data"));
		mockMvc.perform(post("/api/dispatch/orders").contentType("application/json").content("{}"));
		mockMvc.perform(post("/api/dispatch/vehicles").contentType("application/json").content("{}"));

		assertThat(infoLines()).hasSize(4)
				.allSatisfy(line -> assertThat(line).contains("durationMs=").contains("requestId="))
				.extracting(line -> line.substring(0, line.indexOf(' ')))
				.containsExactly("GET", "DELETE", "POST", "POST");
	}

	/** Probes run on a schedule; at INFO they would bury real traffic, so they drop to DEBUG. */
	@Test
	void healthAndInfoProbesProduceNoInfoLineButStillOneDebugLine() throws Exception {
		filterLogger.setLevel(Level.DEBUG);

		mockMvc.perform(get("/healthz"));
		mockMvc.perform(get("/info"));

		assertThat(infoLines()).isEmpty();
		List<String> debugLines = appender.list.stream()
				.filter(e -> e.getLevel() == Level.DEBUG)
				.map(ILoggingEvent::getFormattedMessage)
				.toList();
		assertThat(debugLines).hasSize(2).allSatisfy(line -> assertThat(line).contains("durationMs="));
	}

}
