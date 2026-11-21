package com.bunny.dispatch.repository;

import com.bunny.dispatch.TestcontainersConfiguration;
import com.bunny.dispatch.entity.VehicleEntity;
import com.bunny.dispatch.entity.common.GeoPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class VehicleRepositoryIT {

	@Autowired
	private VehicleRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void embeddedCurrentPointMapsToPrefixedColumns() {
		repository.saveAndFlush(vehicle("VEH001", 100));

		String stored = jdbcTemplate.queryForObject(
				"SELECT current_address || '|' || current_latitude || '|' || current_longitude "
						+ "FROM vehicles WHERE vehicle_id = 'VEH001'", String.class);
		assertThat(stored).contains("28.7041").contains("77.1025");
	}

	@Test
	void insertAndUpsertKeepSingleRow() {
		repository.save(vehicle("VEH001", 100));
		repository.save(vehicle("VEH001", 120));

		assertThat(repository.count()).isEqualTo(1);
		assertThat(repository.findById("VEH001").orElseThrow().getCapacity()).isEqualTo(120);
	}

	@Test
	void deleteAllInBatchEmptiesTable() {
		repository.save(vehicle("VEH001", 100));
		repository.deleteAllInBatch();
		assertThat(repository.count()).isZero();
	}

	private static VehicleEntity vehicle(String id, int capacity) {
		return VehicleEntity.builder()
				.vehicleId(id)
				.capacity(capacity)
				.currentPoint(new GeoPoint(28.7041, 77.1025))
				.currentAddress("Karol Bagh, Delhi, India")
				.build();
	}

}
