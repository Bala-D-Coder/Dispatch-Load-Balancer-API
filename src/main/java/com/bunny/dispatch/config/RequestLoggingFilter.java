package com.bunny.dispatch.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every HTTP call one id and exactly one INFO line, so a Postman request can be found in
 * Render's flat log stream by the id its own response carries back in {@code X-Request-Id}.
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE} so the measured duration covers validation, the service
 * transaction and serialisation rather than only the controller, and so the id is in the MDC
 * before anything else can log.
 *
 * <p>A client-supplied id is honoured only if it matches {@link #CLIENT_ID}: the header is
 * untrusted input that is echoed into a response header and into every log line, so CRLF and
 * control characters must not be reachable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";

	private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
	private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	/** The keep-alive pinger hits the health probe on a schedule; those lines would own the log. */
	private static final Set<String> QUIET_PATHS = Set.of("/healthz", "/info");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = requestIdOf(request);
		response.setHeader(REQUEST_ID_HEADER, requestId);
		MDC.put(MDC_KEY, requestId);
		long started = System.nanoTime();
		try {
			chain.doFilter(request, response);
		} finally {
			long durationMs = (System.nanoTime() - started) / 1_000_000;
			String line = "{} {} status={} durationMs={} requestId={}";
			Object[] fields = {request.getMethod(), request.getRequestURI(), response.getStatus(),
					durationMs, requestId};
			if (QUIET_PATHS.contains(request.getRequestURI())) {
				log.debug(line, fields);
			} else {
				log.info(line, fields);
			}
			MDC.remove(MDC_KEY);
		}
	}

	private static String requestIdOf(HttpServletRequest request) {
		String incoming = request.getHeader(REQUEST_ID_HEADER);
		return (incoming != null && CLIENT_ID.matcher(incoming).matches())
				? incoming
				: UUID.randomUUID().toString();
	}

}
