-- SQL Enhancer Integration Test Data Setup
-- Creates schema and loads test data for comparing SQL enhancer performance

-- Drop tables if they exist;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS customers CASCADE;
DROP TABLE IF EXISTS regions CASCADE;

-- Create regions table (100 rows);
CREATE TABLE regions (
    region_id SERIAL PRIMARY KEY,
    region_name VARCHAR(100) NOT NULL,
    country VARCHAR(100) NOT NULL
);

-- Create customers table (5000 rows);
CREATE TABLE customers (
    customer_id SERIAL PRIMARY KEY,
    customer_name VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL,
    region_id INT NOT NULL REFERENCES regions(region_id),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT now()
);

-- Create orders table (10000 rows);
CREATE TABLE orders (
    order_id SERIAL PRIMARY KEY,
    customer_id INT NOT NULL REFERENCES customers(customer_id),
    amount DECIMAL(10, 2) NOT NULL,
    order_ts TIMESTAMP DEFAULT now(),
    order_status VARCHAR(20) NOT NULL
);

-- Insert 500 regions;
INSERT INTO regions (region_name, country)
SELECT 
    'Region ' || i,
    CASE (i % 10)
        WHEN 0 THEN 'USA'
        WHEN 1 THEN 'Canada'
        WHEN 2 THEN 'UK'
        WHEN 3 THEN 'Germany'
        WHEN 4 THEN 'France'
        WHEN 5 THEN 'Spain'
        WHEN 6 THEN 'Italy'
        WHEN 7 THEN 'Australia'
        WHEN 8 THEN 'Japan'
        ELSE 'Brazil'
    END
FROM generate_series(1, 500) AS i;

-- Insert 25000 customers;
INSERT INTO customers (customer_name, email, region_id, status, created_at)
SELECT 
    'Customer ' || i,
    'customer' || i || '@example.com',
    ((i - 1) % 100) + 1, -- Distribute across all regions
    CASE (i % 5)
        WHEN 0 THEN 'ACTIVE'
        WHEN 1 THEN 'ACTIVE'
        WHEN 2 THEN 'ACTIVE'
        WHEN 3 THEN 'INACTIVE'
        ELSE 'PENDING'
    END,
    now() - interval '1 day' * ((i % 365))
FROM generate_series(1, 25000) AS i;

-- Insert 50000 orders;
INSERT INTO orders (customer_id, amount, order_ts, order_status)
SELECT 
    ((i - 1) % 5000) + 1, -- Distribute across all customers
    (random() * 1000)::DECIMAL(10, 2), -- Random amount between 0 and 1000
    now() - interval '1 day' * ((i % 60)), -- Orders within last 60 days
    CASE (i % 4)
        WHEN 0 THEN 'COMPLETED'
        WHEN 1 THEN 'COMPLETED'
        WHEN 2 THEN 'PENDING'
        ELSE 'CANCELLED'
    END
FROM generate_series(1, 50000) AS i;

-- Create indexes for better performance (realistic scenario);
CREATE INDEX idx_customers_region_id ON customers(region_id);
CREATE INDEX idx_customers_status ON customers(status);
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_order_ts ON orders(order_ts);

-- Analyze tables for better query planning;
ANALYZE regions;
ANALYZE customers;
ANALYZE orders;
