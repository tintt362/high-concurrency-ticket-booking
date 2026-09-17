# High-Concurrency Ticket Booking System

A high-performance ticket booking system designed for flash-sale scenarios.  
The system focuses on **preventing overselling**, handling high concurrent traffic, ensuring data consistency between Redis and MySQL, and optimizing database performance.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)
![Redis](https://img.shields.io/badge/Redis-7.0-red)
![MySQL](https://img.shields.io/badge/MySQL-8.0-blue)



# ⚠️ Note
This project is intended for learning and demonstration purposes only.
This project was built for educational and research purposes.
The included environment configuration files are provided to help reviewers and developers set up and run the project quickly. All configuration information is intended solely for the development environment and does not contain any sensitive data, secrets, or configurations used in a production environment.

## Tech Stack

- **Java 21**
- **Spring Boot 3.2**
- **Spring Data JPA + Hibernate**
- **MySQL**
- **Redis** (with Lua scripts)
- **Spring Actuator**
- **Guava Cache**
- **JMeter** (for load testing)

## Key Features

- Prevent ticket overselling under high concurrency using **Redis Lua scripts** + MySQL conditional updates
- Handle race conditions and row lock timeout issues
- Redis rollback / compensation mechanism when MySQL transaction fails
- Local cache (Guava) + Redis with cache warm-up for fast ticket detail reading
- Connection pool optimization (HikariCP)
- Database optimization: indexes, monthly partitioned tables (`order_yyyyMM`), cursor-based pagination
- Comprehensive load testing with JMeter

## Performance Results (JMeter)

| API                    | Concurrent Users | Throughput     | Error Rate | P95     | P99     |
|------------------------|------------------|----------------|------------|---------|---------|
| Ticket Detail (Read)   | -                | **1,928+ req/s** | **0%**     | 2ms     | 5ms     |
| Booking (Write)        | 500 threads      | 117.7 req/s    | **0%**     | 56.2ms  | 75.2ms  |

-> Read Data <img width="1769" height="617" alt="jmetter_read" src="https://github.com/user-attachments/assets/0f9dd27c-2838-4de6-9156-65a419718ff6" />

-> Write Data <img width="1589" height="528" alt="jmetter" src="https://github.com/user-attachments/assets/fa00ce52-8f13-4872-b39c-b37181c3daba" />

> Tested with 1,000 requests on the booking API.

## System Design Highlights

### 1. Prevent Overselling
- Used **Redis Lua script** to atomically check and decrease ticket stock
- Combined with MySQL conditional update to ensure consistency
- Implemented compensation/rollback when MySQL transaction fails after Redis stock reduction

### 2. Fix Row Lock & Connection Issues
- Resolved `Lock wait timeout exceeded` by reducing transaction scope
- Fixed HikariCP connection leak under high load by shortening long-held connections

### 3. Caching Strategy
- Guava Local Cache + Redis
- Cache warm-up to reduce ticket-detail response time

### 4. Database Optimization
- Added proper indexes
- Monthly order tables (`order_yyyyMM`)

## Project Structure

## 🏗 Architecture Diagram
<img width="4628" height="2420" alt="architech" src="https://github.com/user-attachments/assets/3a14e124-e9ba-488a-b930-e0530e51468c" />
<img width="1940" height="1744" alt="banvetautet-kafka" src="https://github.com/user-attachments/assets/5c180611-db79-4a15-82e0-d9c36ca4fe6e" />



## 🚀 Cách chạy project

### Prerequisites
- Java 21+
- MySQL 8
- Redis 7
- Maven

### Chạy local

```bash
# 1. Clone repo
git clone https://github.com/tintt362/high-concurrency-ticket-booking.git
cd high-concurrency-ticket-booking

# 2. Cấu hình database & Redis trong application.yml

# 3. Build & Run
mvn clean spring-boot:run


---
```
**Thai Trong Tin**
Aspiring Java Backend Developer

* 🎓 Industrial University of Ho Chi Minh City (IUH)
* 📍 Ho Chi Minh City, Vietnam
* ✉️ Email: tintt362@gmail.com
* 🔗 LinkedIn: https://www.linkedin.com/in/thai-trong-tin-6a1529332
