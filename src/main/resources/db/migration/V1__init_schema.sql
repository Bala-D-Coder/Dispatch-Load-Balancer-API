-- V1: dispatch schema. No CHECK / NOT NULL beyond PKs — validation authority is the code layer.
CREATE TABLE IF NOT EXISTS orders (
    order_id       VARCHAR(64)  PRIMARY KEY,
    latitude       DOUBLE PRECISION,
    longitude      DOUBLE PRECISION,
    address        VARCHAR(512),
    package_weight INTEGER,
    priority       VARCHAR(10),
    created_at     TIMESTAMP,
    updated_at     TIMESTAMP
);

CREATE TABLE IF NOT EXISTS vehicles (
    vehicle_id        VARCHAR(64)  PRIMARY KEY,
    capacity          INTEGER,
    current_latitude  DOUBLE PRECISION,
    current_longitude DOUBLE PRECISION,
    current_address   VARCHAR(512),
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP
);
