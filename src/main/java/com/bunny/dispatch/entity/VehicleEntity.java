package com.bunny.dispatch.entity;

import com.bunny.dispatch.entity.common.GeoPoint;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "vehicles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleEntity {

	@Id
	@Column(name = "vehicle_id", length = 64)
	private String vehicleId;

	@Column(name = "capacity")
	private Integer capacity;

	@Embedded
	@AttributeOverride(name = "latitude", column = @Column(name = "current_latitude"))
	@AttributeOverride(name = "longitude", column = @Column(name = "current_longitude"))
	private GeoPoint currentPoint;

	@Column(name = "current_address", length = 512)
	private String currentAddress;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
		updatedAt = createdAt;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = LocalDateTime.now();
	}

}
