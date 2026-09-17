# High-Concurrency Ticket Booking System

Hệ thống đặt vé sự kiện **high-concurrency** (hàng nghìn request/giây) được xây dựng để mô phỏng flash-sale thực tế mà không bị overselling.

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)
![Redis](https://img.shields.io/badge/Redis-7.0-red)
![MySQL](https://img.shields.io/badge/MySQL-8.0-blue)



# ⚠️ Lưu ý
This project is intended for learning and demonstration purposes only.

Dự án này được xây dựng với mục đích học tập, nghiên cứu.
Các tệp cấu hình môi trường được đính kèm nhằm giúp người đánh giá và nhà phát triển có thể thiết lập và chạy dự án một cách nhanh chóng. Toàn bộ thông tin cấu hình chỉ phục vụ cho môi trường phát triển (development), không chứa dữ liệu nhạy cảm, thông tin bí mật hay bất kỳ cấu hình nào được sử dụng trong môi trường sản xuất (production).
## ✨ Tính năng nổi bật

- **Xử lý 2.000+ orders/giây** trong flash-sale mà không overselling.
  - **Giảm latency** từ >500ms → **<60ms** nhờ 2-level caching (Guava + Redis).
  - **Distributed Lock** với Redisson đảm bảo tính nhất quán.
  - **Atomic stock deduction** bằng Redis Lua Script + CAS + MySQL transaction.
  - **Audit Log** bất đồng bộ với Elasticsearch.
  - **Monthly table sharding** cho bảng order.
  - **Monitoring** realtime với Spring Actuator + Prometheus + Grafana.
  - **Rollback compensation** khi transaction fail.

## 🛠 Tech Stack

**Backend:** Java 17, Spring Boot 3.2, Spring Data JPA, Hibernate  
**Database:** MySQL 8, Redis 7  
**Caching & Lock:** Guava Cache, Redis Lua Script, Redisson Distributed Lock  
**Search & Logging:** Elasticsearch, Spring Actuator + Prometheus + Grafana  
**Others:** Docker, Maven, Lombok

## 📊 Benchmark

- **Throughput**: 2.000+ orders/sec (flash-sale simulation)
  - **GET /ticket/detail**: <60ms (sau cache warm-up)
  - **Concurrency test**: 9.000+ requests/sec (JMeter)

## 🚀 Cách chạy project

### Prerequisites
- Java 17+
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