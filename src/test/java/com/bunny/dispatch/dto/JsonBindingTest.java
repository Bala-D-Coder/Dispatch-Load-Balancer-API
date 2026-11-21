package com.bunny.dispatch.dto;

import com.bunny.dispatch.dto.request.OrderRequest;
import com.bunny.dispatch.dto.request.OrdersBatchRequest;
import com.bunny.dispatch.entity.common.Priority;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonBindingTest {

	// Jackson 3 flipped FAIL_ON_UNKNOWN_PROPERTIES to disabled by default; our app
	// re-enables it via application.yaml, so the test mapper must mirror that config.
	private final ObjectMapper mapper = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	@Test
	void specSampleOrderBindsAllFields() throws Exception {
		String json = """
				{ "orderId": "ORD001", "latitude": 12.9716, "longitude": 77.5946,
				  "address": "MG Road, Bangalore, Karnataka, India", "packageWeight": 10, "priority": "HIGH" }
				""";
		OrderRequest order = mapper.readValue(json, OrderRequest.class);
		assertThat(order.orderId()).isEqualTo("ORD001");
		assertThat(order.latitude()).isEqualTo(12.9716);
		assertThat(order.longitude()).isEqualTo(77.5946);
		assertThat(order.address()).isEqualTo("MG Road, Bangalore, Karnataka, India");
		assertThat(order.packageWeight()).isEqualTo(10);
		assertThat(order.priority()).isEqualTo(Priority.HIGH);
	}

	@Test
	void lowercasePriorityIsRejected() {
		String json = """
				{ "orderId": "ORD001", "latitude": 12.9716, "longitude": 77.5946,
				  "address": "MG Road", "packageWeight": 10, "priority": "high" }
				""";
		assertThatThrownBy(() -> mapper.readValue(json, OrderRequest.class))
				.isInstanceOf(MismatchedInputException.class);
	}

	@Test
	void unknownPriorityValueIsRejected() {
		String json = """
				{ "orderId": "ORD001", "latitude": 12.9716, "longitude": 77.5946,
				  "address": "MG Road", "packageWeight": 10, "priority": "URGENT" }
				""";
		assertThatThrownBy(() -> mapper.readValue(json, OrderRequest.class))
				.isInstanceOf(MismatchedInputException.class);
	}

	@Test
	void unknownFieldIsRejected() {
		String json = """
				{ "orderId": "ORD001", "latitude": 12.9716, "longitude": 77.5946,
				  "address": "MG Road", "packageWeight": 10, "priority": "HIGH", "foo": 1 }
				""";
		assertThatThrownBy(() -> mapper.readValue(json, OrderRequest.class))
				.isInstanceOf(UnrecognizedPropertyException.class);
	}

	@Test
	void batchWrapperBindsUnderOrdersKey() throws Exception {
		String json = """
				{ "orders": [
				    { "orderId": "ORD001", "latitude": 12.9716, "longitude": 77.5946,
				      "address": "MG Road", "packageWeight": 10, "priority": "HIGH" },
				    { "orderId": "ORD002", "latitude": 13.0827, "longitude": 80.2707,
				      "address": "Anna Salai", "packageWeight": 20, "priority": "MEDIUM" }
				] }
				""";
		OrdersBatchRequest batch = mapper.readValue(json, OrdersBatchRequest.class);
		assertThat(batch.orders()).hasSize(2);
	}

	@Test
	void emptyOrdersListFailsNotEmpty() throws Exception {
		OrdersBatchRequest batch = mapper.readValue("{\"orders\":[]}", OrdersBatchRequest.class);
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			Validator validator = factory.getValidator();
			assertThat(validator.validate(batch))
					.anyMatch(v -> v.getPropertyPath().toString().equals("orders"));
		}
	}

	@Test
	void missingOrdersKeyBindsToNullAndFailsNotEmpty() throws Exception {
		OrdersBatchRequest batch = mapper.readValue("{}", OrdersBatchRequest.class);
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			Validator validator = factory.getValidator();
			assertThat(validator.validate(batch))
					.anyMatch(v -> v.getPropertyPath().toString().equals("orders"));
		}
	}

}
