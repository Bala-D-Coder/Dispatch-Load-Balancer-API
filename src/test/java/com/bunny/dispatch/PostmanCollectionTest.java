package com.bunny.dispatch;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Documentation drift fence: the Postman collection is the reviewer's entry point, so it is
 * checked by the build instead of by hope. The demo bodies in the collection must equal the very
 * sample files the integration tests post, every error request must assert the {@code code} our
 * handler actually returns, and nothing may point at a host other than localhost.
 *
 * <p>Reads the collection from the module directory, so it is skipped (not failed) when surefire
 * runs from elsewhere.
 */
class PostmanCollectionTest {

	private static final Path COLLECTION = Path.of("postman", "dispatch-load-balancer.postman_collection.json");
	private static final Path SAMPLES = Path.of("src", "test", "resources", "sample");
	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final Set<String> EXPECTED_CODES = Set.of("VALIDATION_FAILED", "MALFORMED_JSON",
			"DUPLICATE_IN_BATCH", "UNSUPPORTED_MEDIA_TYPE", "METHOD_NOT_ALLOWED", "ENDPOINT_NOT_FOUND",
			"NO_DISPATCH_DATA");
	private static final Set<String> EXPECTED_PATHS = Set.of("/healthz", "/api/dispatch/orders",
			"/api/dispatch/vehicles", "/api/dispatch/plan", "/api/dispatch/data");

	@Test
	void collectionIsPostmanV21AndPointsAtLocalhostOnly() {
		Map<String, Object> info = asMap(collection().get("info"));
		assertThat(info.get("schema"))
				.isEqualTo("https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
		assertThat((String) info.get("_postman_id")).isNotBlank();
		assertThat(variables()).extracting(v -> v.get("value"))
				.containsExactly("http://localhost:8080");
	}

	@Test
	void everyRequestUsesTheBaseUrlVariableAndCarriesDocumentation() {
		for (Request request : requests()) {
			assertThat(request.rawUrl()).startsWith("{{baseUrl}}/");
			assertThat(request.description()).as("description of %s", request.name()).isNotBlank();
			assertThat(request.testScript()).as("assertions of %s", request.name()).contains("pm.test");
		}
	}

	@Test
	void demoFolderPayloadsAreTheCommittedSampleFilesVerbatim() throws IOException {
		// the reviewer's Postman demo and the build's golden test must be the same bytes, or they are two stories
		assertThat(jsonBodies(folder("1 -"))).containsExactly(
				readSample("delhi-vehicles.json"), readSample("delhi-orders.json"));
		assertThat(jsonBodies(folder("2 -"))).containsExactly(
				readSample("bangalore-vehicles.json"), readSample("bangalore-orders.json"));
	}

	@Test
	void errorFolderCoversEveryErrorCodeAndAssertsStatus() {
		List<Request> errors = folder("3 -").requests();
		Set<String> asserted = new LinkedHashSet<>();
		for (Request request : errors) {
			assertThat(request.testScript()).as("%s asserts a status", request.name())
					.contains("pm.response.to.have.status");
			asserted.addAll(codesAssertedIn(request.testScript()));
		}
		assertThat(asserted).containsExactlyInAnyOrderElementsOf(EXPECTED_CODES);
	}

	@Test
	void collectionCoversEveryEndpointAndNothingElse() {
		Set<String> unknownPathProbe = Set.of("/api/dispatch/nope");
		Set<String> paths = new LinkedHashSet<>();
		requests().forEach(r -> paths.add(r.rawUrl().replace("{{baseUrl}}", "")));
		assertThat(paths).containsAll(EXPECTED_PATHS);
		paths.removeAll(EXPECTED_PATHS);
		assertThat(paths).containsExactlyElementsOf(unknownPathProbe);
	}

	private static List<Object> jsonBodies(Folder folder) throws IOException {
		List<Object> bodies = new ArrayList<>();
		for (Request request : folder.requests()) {
			for (String raw : request.rawBody().stream().toList()) {
				bodies.add(MAPPER.readValue(raw, Object.class));
			}
		}
		return bodies;
	}

	private static Object readSample(String name) throws IOException {
		return MAPPER.readValue(Files.readString(SAMPLES.resolve(name), StandardCharsets.UTF_8), Object.class);
	}

	private static Set<String> codesAssertedIn(String script) {
		Set<String> codes = new LinkedHashSet<>();
		for (String code : EXPECTED_CODES) {
			if (script.contains("body.code).to.eql(\"" + code + "\")")) {
				codes.add(code);
			}
		}
		return codes;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object node) {
		return (Map<String, Object>) node;
	}

	private static Map<String, Object> collection() {
		assumeTrue(Files.exists(COLLECTION), "collection is read from the module directory");
		try {
			return asMap(MAPPER.readValue(Files.readString(COLLECTION, StandardCharsets.UTF_8), Object.class));
		}
		catch (IOException ex) {
			throw new IllegalStateException("postman collection does not parse: " + ex.getMessage(), ex);
		}
	}

	private static List<Map<String, Object>> variables() {
		return asList(collection().get("variable"));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> asList(Object node) {
		return (List<Map<String, Object>>) node;
	}

	private static List<Map<String, Object>> folders() {
		return asList(collection().get("item"));
	}

	private static Folder folder(String namePrefix) {
		return folders().stream()
				.filter(f -> ((String) f.get("name")).startsWith(namePrefix))
				.findFirst()
				.map(f -> new Folder((String) f.get("name"), asList(f.get("item"))))
				.orElseThrow(() -> new AssertionError("no folder starting with " + namePrefix));
	}

	private static List<Request> requests() {
		List<Request> all = new ArrayList<>();
		folders().forEach(f -> all.addAll(new Folder((String) f.get("name"), asList(f.get("item"))).requests()));
		return all;
	}

	private record Folder(String name, List<Map<String, Object>> items) {

		List<Request> requests() {
			return items.stream().map(i -> new Request(name, i)).toList();
		}

	}

	private record Request(String folder, Map<String, Object> item) {

		String name() {
			return (String) item.get("name");
		}

		String rawUrl() {
			return (String) asMap(asMap(item.get("request")).get("url")).get("raw");
		}

		String description() {
			return (String) asMap(item.get("request")).get("description");
		}

		Optional<String> rawBody() {
			Object body = asMap(item.get("request")).get("body");
			return body == null ? Optional.empty() : Optional.of((String) asMap(body).get("raw"));
		}

		String testScript() {
			StringBuilder script = new StringBuilder();
			for (Map<String, Object> event : asList(item.get("event"))) {
				for (Object line : (List<?>) asMap(event.get("script")).get("exec")) {
					script.append(line).append('\n');
				}
			}
			return script.toString();
		}

	}

}
