package com.bunny.dispatch.repository;

import com.bunny.dispatch.entity.VehicleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<VehicleEntity, String> {
}
