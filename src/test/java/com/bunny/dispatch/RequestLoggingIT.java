package com.bunny.dispatch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bunny.dispatch.config.RequestLoggingFilter;
import com.bunny.dispatch.service.dispatch.impl.DispatchServiceImpl;
import com.bunny.dispatch.support.SampleData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * P-12.1 through the real servlet chain: the filter must actually be registered, the id must
 * survive into the MDC of the service layer, and the plan call must be attributable to a phase.
 *
 * <p>Runs on the shared E2E context (one container) and uses the Bangalore sample, whose goldens
 * every other E2E class already pins — the numbers here are only ever read from the logs.
 */
class RequestLoggingIT extends AbstractE2ETest {

	private ListAppender<ILoggingEvent> httpAppender;
	private ListAppender<ILoggingEvent> serviceAppender;
	private Logger httpLogger;
	private Logger serviceLogger;

	/** The published console pattern, read back so the test asserts on the deployed format. */
	@Value("${logging.pattern.console}")
	private String consolePattern;

	@BeforeEach
	void attachAppenders() {
		httpLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
		serviceLogger = (Logger) LoggerFactory.getLogger(DispatchServiceImpl.class);
		httpAppender = new ListAppender<>();
		serviceAppender = new ListAppender<>();
		httpAppender.start();
		serviceAppender.start();
		httpLogger.addAppender(httpAppender);
		serviceLogger.addAppender(serviceAppender);
	}

	@AfterEach
	void detachAppenders() {
		// null when the shared context itself failed to start: the superclass @BeforeEach runs first
		if (httpLogger != null) {
			httpLogger.detachAppender(httpAppender);
			serviceLogger.detachAppender(serviceAppender);
		}
	}

	private List<String> httpLines() {
		return httpAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	private List<String> serviceLines() {
		return serviceAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	private static Pattern phasePattern(String name) {
		return Pattern.compile(name + "=([0-9.]+)");
	}

	private static double phaseOf(String line, String name) {
		Matcher matcher = phasePattern(name).matcher(line);
		assertThat(matcher.find()).as("%s present in %s", name, line).isTrue();
		return Double.parseDouble(matcher.group(1));
	}

	@Test
	void everyHttpCallIsLoggedOnceWithTheIdTheResponseEchoesAndTheServiceSees() throws Exception {
		postSample(VEHICLES_URL, SampleData.vehiclesJson("bangalore-vehicles.json"));
		postSample(ORDERS_URL, SampleData.ordersJson("bangalore-orders.json"));
		httpAppender.list.clear();
		serviceAppender.list.clear();

		MvcResult result = mockMvc.perform(get(PLAN_URL)).andReturn();
		String issued = result.getResponse().getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);

		assertThat(httpLines()).hasSize(1);
		assertThat(httpLines().get(0))
				.startsWith("GET /api/dispatch/plan status=200 durationMs=")
				.endsWith("requestId=" + issued);
		// the MDC is what carries the id into every other line of the same request
		assertThat(httpAppender.list.get(0).getMDCPropertyMap())
				.containsEntry(RequestLoggingFilter.MDC_KEY, issued);

		List<ILoggingEvent> planEvents = serviceAppender.list.stream()
				.filter(event -> event.getFormattedMessage().startsWith("buildPlan "))
				.toList();
		assertThat(planEvents).hasSize(1);
		assertThat(planEvents.get(0).getMDCPropertyMap())
				.containsEntry(RequestLoggingFilter.MDC_KEY, issued);
	}

	/**
	 * The pattern lives in {@code application.yaml}, where a typo degrades silently: logback still
	 * boots, it just prints {@code %PARSER_ERROR} instead of the id (found exactly this way — the
	 * nested {@code ${VAR:-default}} tokens Boot uses internally are not resolvable from
	 * {@code logging.pattern.console}). Rendering a captured event through the published pattern is
	 * the only proof that the line a reviewer reads carries the correlation id.
	 */
	@Test
	void publishedConsolePatternRendersTheRequestIdOfTheCall() throws Exception {
		MvcResult result = mockMvc.perform(get(PLAN_URL)).andReturn();
		String issued = result.getResponse().getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
		assertThat(httpLines()).hasSize(1);

		PatternLayout layout = new PatternLayout();
		layout.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
		layout.setPattern(consolePattern);
		layout.start();
		assertThat(layout.isStarted()).isTrue();
		String rendered = layout.doLayout(httpAppender.list.get(0));
		layout.stop();

		assertThat(rendered)
				.contains("[" + issued + "]")
				.contains("requestId=" + issued)
				.doesNotContain("PARSER_ERROR");
	}

	@Test
	void healthProbeLogsNoInfoLineThroughTheRealChain() throws Exception {
		mockMvc.perform(get("/healthz"));

		assertThat(httpLines()).isEmpty();
	}

	/**
	 * The attribution claim Part 12 exists for: on a warm instance a plan call is dominated by the
	 * two DB loads, not by the algorithm. Asserted on the second call so nothing is measured cold.
	 */
	@Test
	void planLineNamesThePhaseThatDominatesAWarmCall() throws Exception {
		postSample(VEHICLES_URL, SampleData.vehiclesJson("bangalore-vehicles.json"));
		postSample(ORDERS_URL, SampleData.ordersJson("bangalore-orders.json"));

		plan();
		serviceAppender.list.clear();
		plan();

		List<String> planLines = serviceLines().stream()
				.filter(line -> line.startsWith("buildPlan "))
				.toList();
		assertThat(planLines).hasSize(1);
		String line = planLines.get(0);

		double loadOrdersMs = phaseOf(line, "loadOrdersMs");
		double loadVehiclesMs = phaseOf(line, "loadVehiclesMs");
		double assignAndRouteMs = phaseOf(line, "assignAndRouteMs");
		double mapMs = phaseOf(line, "mapMs");

		assertThat(loadOrdersMs).isGreaterThanOrEqualTo(0);
		assertThat(loadVehiclesMs).isGreaterThanOrEqualTo(0);
		assertThat(mapMs).isGreaterThanOrEqualTo(0);
		assertThat(loadOrdersMs + loadVehiclesMs)
				.as("DB loads dominate the in-memory assignment: %s", line)
				.isGreaterThan(assignAndRouteMs);
	}

}
