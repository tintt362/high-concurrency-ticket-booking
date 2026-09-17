package com.trongtin.asyncprocessingsys.service.order.impl;

import com.trongtin.asyncprocessingsys.cache.distributed.RedisDistributedLocker;
import com.trongtin.asyncprocessingsys.cache.distributed.RedisDistributedService;
import com.trongtin.asyncprocessingsys.dto.response.PagedOrdersDTO;
import com.trongtin.asyncprocessingsys.dto.response.PlaceOrderResponse;
import com.trongtin.asyncprocessingsys.dto.response.TicketOrderDTO;
import com.trongtin.asyncprocessingsys.model.audit.OrderAuditLog;
import com.trongtin.asyncprocessingsys.model.entity.TickerOrder;
import com.trongtin.asyncprocessingsys.repository.ticket.TicketOrderRepository;
import com.trongtin.asyncprocessingsys.service.audit.OrderAuditLogService;
import com.trongtin.asyncprocessingsys.service.order.OrderDeductionService;
import com.trongtin.asyncprocessingsys.service.order.StockTransactionService;
import com.trongtin.asyncprocessingsys.service.order.TicketOrderService;
import com.trongtin.asyncprocessingsys.service.order.cache.StockOrderCacheService;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TicketOrderServiceImpl implements TicketOrderService {

    @Autowired
    private TicketOrderRepository ticketOrderRepository;

    @Autowired
    private OrderDeductionService orderDeductionService;
    @Autowired
    private StockOrderCacheService stockOrderCacheService;

    @Autowired
    private RedisDistributedService redisDistributedService;

    @Autowired
    private StockTransactionService stockTransactionService;

    @Autowired
    OrderTransactionService orderTransactionService;

    @Autowired
    private OrderAuditLogService auditLogService;

    // SELECT
    // UPDATE
    @Override
    @Transactional
    public boolean decreaseStock1(Long tickerId, int quantity) {
        try {

            int stockAvailable = ticketOrderRepository.getStockAvailable(tickerId); // LOCK MYSQL -> SELECT ... FOR UPDATE;
            if (stockAvailable < quantity) {
                log.info("Case: stockAvailable < quantity | {}, {}", stockAvailable, quantity);
                return false;
            }
            return ticketOrderRepository.decreaseStock1(tickerId, quantity);
        } catch (PessimisticLockException e) {
            log.warn("Pessimistic Locking failed for ticketId={}", tickerId);
            return false;
        } catch (LockTimeoutException e) {
            log.error("Lock timeout while processing ticketId={}", tickerId, e);
            return false;
        } catch (Exception e) {
            log.error("Unexpected error when decreasing stock for ticketId={}", tickerId, e);
            return false;
        }
    }

    // UPDATE product SET stock = stock - quantity WHERE productId = 1 AND stock > 0; > quantity -> LOCK row -> innodb MYSQL
    // version -> Lock thread
    // UPDATE product SET stock = stock - quantity, version = version + 1 WHERE productId = 1 AND stock > 0 AND version = 10;

    @Override
    public PlaceOrderResponse placeOrderCAS(Long ticketId, int quantity) {

        // =========================================================
        // 1. Validate input
        // =========================================================
        if (ticketId == null || quantity <= 0) {
            return PlaceOrderResponse.failed(
                    "INVALID_REQUEST",
                    "Thông tin đặt vé không hợp lệ"
            );
        }

        // =========================================================
        // 2. Lấy price TRƯỚC khi reserve stock
        //    Không cần DB transaction ở bước này
        // =========================================================
        long unitPrice =
                stockOrderCacheService.getEffectivePrice(ticketId);

        if (unitPrice <= 0) {
            return PlaceOrderResponse.failed(
                    "PRICE_NOT_FOUND",
                    "Không thể xác định giá vé"
            );
        }

        // =========================================================
        // 3. Redis Lua = Atomic Gate
        // =========================================================
        int redisResult =
                stockOrderCacheService.decreaseStockCacheByLUA(
                        ticketId,
                        quantity
                );
        log.info(
                "redisResult ticketId={}",
                redisResult
        );

        // =========================================================
        // 4. Cache miss → warm-up → retry
        // =========================================================
        if (redisResult == -1) {

            log.info(
                    "placeOrderCAS: cache miss, warming up ticketId={}",
                    ticketId
            );

            boolean warmed =
                    stockOrderCacheService
                            .addStockAvailableToCache(ticketId);

            if (!warmed) {
                return PlaceOrderResponse.failed(
                        "TICKET_NOT_FOUND",
                        "Không tìm thấy sự kiện"
                );
            }

            redisResult =
                    stockOrderCacheService.decreaseStockCacheByLUA(
                            ticketId,
                            quantity
                    );
        }

        // =========================================================
        // 5. Redis atomic gate reject
        // =========================================================
        if (redisResult == 0) {

            log.info(
                    "placeOrderCAS: insufficient stock, ticketId={}",
                    ticketId
            );

            return PlaceOrderResponse.failed(
                    "OUT_OF_STOCK",
                    "Hết vé, vui lòng thử lại sau"
            );
        }

        // =========================================================
        // 6. Redis đã reserve stock
        //    Bắt đầu DB transaction
        // =========================================================
        try {

            PlaceOrderResponse response =
                    orderTransactionService.createOrder(
                            ticketId,
                            quantity,
                            unitPrice
                    );

            // =====================================================
            // 7. DB transaction SUCCESS → Redis reservation giữ nguyên
            // =====================================================

            return response;

        } catch (Exception e) {

            // =====================================================
            // 8. DB transaction FAILED
            //    MySQL đã rollback
            //    Redis không tự rollback → compensation
            // =====================================================

            log.error(
                    "placeOrderCAS: DB transaction failed, " +
                            "compensating Redis stock. ticketId={}",
                    ticketId,
                    e
            );

            try {

                stockOrderCacheService.increaseStockCache(
                        ticketId,
                        quantity
                );

            } catch (Exception compensationException) {

                // =================================================
                // Compensation cũng fail
                // → cần retry / recovery / reconciliation
                // =================================================

                log.error(
                        "CRITICAL: Redis compensation failed. " +
                                "ticketId={}, quantity={}",
                        ticketId,
                        quantity,
                        compensationException
                );

                // Production:
                // → push compensation event/task
                // → retry
                // → reconciliation
            }

            return PlaceOrderResponse.failed(
                    "SERVER_ERROR",
                    "Lỗi hệ thống, vui lòng thử lại"
            );
        }
    }
    //  @Override
    @Transactional(rollbackFor = Exception.class)
    public PlaceOrderResponse placeOrderCAS1(Long ticketId, int quantity) {
        boolean isRedisDecremented = false;
        Integer oldStock = null;
        try {
            // LẤY OLD STOCK TRƯỚC
            oldStock = ticketOrderRepository.getStockAvailable(ticketId);
            int redisResult = stockOrderCacheService.decreaseStockCacheByLUA(ticketId, quantity);
            if (redisResult == -1) {
                // Cache chưa được warm → load từ DB rồi retry
                log.info("placeOrderCAS: cache miss for ticketId={}, warming up...", ticketId);
                boolean warmed = stockOrderCacheService.addStockAvailableToCache(ticketId);
                if (!warmed) {
                    return PlaceOrderResponse.failed("TICKET_NOT_FOUND", "Không tìm thấy sự kiện");
                }
                redisResult = stockOrderCacheService.decreaseStockCacheByLUA(ticketId, quantity);
            }
            if (redisResult == 0) {
                log.info("placeOrderCAS: Redis stock insufficient for ticketId={}", ticketId);
                return PlaceOrderResponse.failed("OUT_OF_STOCK", "Hết vé, vui lòng thử lại sau");
            }
            isRedisDecremented = true;

            // Redis Lua đã là atomic gate → DB chỉ cần safety net, không cần CAS
            boolean isDecreaseStockSuccess = ticketOrderRepository.decreaseStock1(ticketId, quantity);
            if (!isDecreaseStockSuccess) {
                stockOrderCacheService.increaseStockCache(ticketId, quantity);
                log.warn("placeOrderCAS: DB update failed, rolled back Redis for ticketId={}", ticketId);
                return PlaceOrderResponse.failed("STOCK_CONFLICT", "Đặt vé không thành công, vui lòng thử lại");
            }
            int newStock = oldStock - quantity;
            long unitPrice = stockOrderCacheService.getEffectivePrice(ticketId);
            if (unitPrice <= 0) {
                stockOrderCacheService.increaseStockCache(ticketId, quantity);
//                tickerOrderDomainService.increaseStock(ticketId, quantity); // not TX Trong trường hợp này, nếu không lấy được giá thì có thể do dữ liệu không hợp lệ hoặc lỗi hệ thống. Việc rollback stock
                log.warn("placeOrderCAS: price not found for ticketId={}, rolled back Redis", ticketId);
                return PlaceOrderResponse.failed("PRICE_NOT_FOUND", "Không thể xác định giá vé");
            }

            int userId = ThreadLocalRandom.current().nextInt(1, 10);
            String orderNumber = "OKX-SGN-" + userId + "-" + System.currentTimeMillis();
            String nTable = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
            // TẠO AUDIT LOG
            OrderAuditLog auditLog = OrderAuditLog.createPlaceOrderLog(
                    ticketId, userId, quantity, oldStock, newStock, orderNumber);
            auditLogService.index(auditLog);   // ← GỌI ASYNC

            TickerOrder order = new TickerOrder();
            order.setTicketId(ticketId.intValue());
            order.setQuantity(quantity);
            order.setOrderStatus(0);
            order.setUserId(userId);
            order.setOrderNumber(orderNumber);
            order.setTotalAmount(new BigDecimal(unitPrice * quantity));
            order.setTerminalId("OKX-SGN");
            order.setOrderNotes("Order -> Pending");
            orderDeductionService.insertOrder(nTable, order);

            log.info("placeOrderCAS: success | ticketId={} orderNumber={}", ticketId, orderNumber);
            return PlaceOrderResponse.success(orderNumber);

        } catch (Exception e) {
            log.error("placeOrderCAS: error for ticketId={}", ticketId, e);
            if (isRedisDecremented) stockOrderCacheService.increaseStockCache(ticketId, quantity);

            //             if (isDbDecremented)    tickerOrderDomainService.increaseStock(ticketId, quantity); // not TX
            return PlaceOrderResponse.failed("SERVER_ERROR", "Lỗi hệ thống, vui lòng thử lại");
        }
    }

    @Override
    public int getStockAvailable(Long ticketId) {
        return ticketOrderRepository.getStockAvailable(ticketId);
    }

    @Override
    public List<TicketOrderDTO> findAll(String yearMonth) {
        List<Object[]> results = orderDeductionService.findAll(yearMonth);
        return results.stream().map(row -> new TicketOrderDTO(
                ((Number) row[0]).intValue(),
                ((Number) row[1]).intValue(),
                ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue(),
                ((Number) row[4]).intValue(),
                (String) row[5],
                (BigDecimal) row[6],
                (String) row[7],
                ((Timestamp) row[8]).toLocalDateTime(),
                (String) row[9],
                ((Timestamp) row[10]).toLocalDateTime(),
                ((Timestamp) row[11]).toLocalDateTime()
        )).toList();
    }


    @Override
    public boolean insertOrder(String yearMonth, TickerOrder tickerOrder) {
        orderDeductionService.insertOrder(yearMonth, tickerOrder);
        return true;
    }

    @Override
    public TicketOrderDTO findByOrderNumber(String yearMonth, String orderNumber) {
        String nTable = extractYearMonthFromOrderNumber(orderNumber);
        log.info("nTable: findByOrderNumber = {}", nTable);
        Object[] row = orderDeductionService.findByOrderNumber(nTable, orderNumber);
        if (row == null) {
            log.warn("Order not found with number: {}", orderNumber);
            return null;
        }
        return new TicketOrderDTO(
                ((Number) row[0]).intValue(),
                ((Number) row[1]).intValue(),
                ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue(),
                ((Number) row[4]).intValue(),
                (String) row[5],
                (BigDecimal) row[6],
                (String) row[7],
                ((Timestamp) row[8]).toLocalDateTime(),
                (String) row[9],
                ((Timestamp) row[10]).toLocalDateTime(),
                ((Timestamp) row[11]).toLocalDateTime()
        );
    }


    // chuyển đổi
    private String extractYearMonthFromOrderNumber(String orderNumber) {
        try {
            // Lấy timestamp từ orderNumber
            String[] parts = orderNumber.split("-");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid order number format");
            }
            long timestamp = Long.parseLong(parts[parts.length - 1]);

            // Chuyển đổi timestamp thành LocalDateTime
            LocalDateTime dateTime = Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();

            // Format thành yyyyMM
            return dateTime.format(DateTimeFormatter.ofPattern("yyyyMM"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract yearMonth from orderNumber: " + orderNumber, e);
        }
    }

    @Override
    public PagedOrdersDTO findPage(String yearMonth, long lastId, int limit) {
        List<Object[]> results = orderDeductionService.findPage(yearMonth, lastId, limit);
        List<TicketOrderDTO> items = results.stream().map(row -> new TicketOrderDTO(
                ((Number) row[0]).intValue(),
                ((Number) row[1]).intValue(),
                ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue(),
                ((Number) row[4]).intValue(),
                (String) row[5],
                (java.math.BigDecimal) row[6],
                (String) row[7],
                ((java.sql.Timestamp) row[8]).toLocalDateTime(),
                (String) row[9],
                ((java.sql.Timestamp) row[10]).toLocalDateTime(),
                ((java.sql.Timestamp) row[11]).toLocalDateTime()
        )).toList();

        boolean hasMore = results.size() == limit;
        Long nextCursor = hasMore ? ((Number) results.get(results.size() - 1)[0]).longValue() : null;
        return new PagedOrdersDTO(items, nextCursor, hasMore);
    }

    //Lockey
    private String genTokenLockKey(Long ticketId) {
        return "TOKEN_LOCK_KEY" + ticketId;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelOrder(Long userId, String orderNumber) {
        log.info("cancelOrder | userId: {} | orderNumber: {}", userId, orderNumber);
        Integer oldStock = null;
        // 1. key Lock -> order_number
        String lockKey = "LOCK:CANCEL_ORDER:" + orderNumber;
        RedisDistributedLocker lock = redisDistributedService.getDistributedLock(lockKey);

        try {
            // keep 5 seconds
            boolean isLocked = lock.tryLock(1, 5, TimeUnit.SECONDS);
            if (!isLocked) {
                log.warn("System is processing this order, pls wait.. {}", orderNumber); // => ELK
                return false;
            }

            // 2. Logic..
            // 2. Logic nghiệp vụ (Chỉ thực hiện sau khi đã chiếm được khóa)
            String yearMonth = extractYearMonthFromOrderNumber(orderNumber);
            TicketOrderDTO order = findByOrderNumber(yearMonth, orderNumber);

            if (order == null || !order.getUserId().equals(userId.intValue())) {
                log.error("Order not found or not belong to user: {}", orderNumber);
                return false;
            }

            // Bước check quan trọng nhất: Nếu đã hủy rồi thì thoát ngay
            if (order.getOrderStatus() == 2) {
                log.info("Order already cancelled: {}", orderNumber);
                return true;
            }

            // 3. Cập nhật trạng thái trong Database
            boolean isUpdated = orderDeductionService.updateOrderStatus(yearMonth, orderNumber, 2);
            if (!isUpdated) {
                log.error("Failed to update status to CANCELLED: {}", orderNumber);
                return false;
            }

            // 4. Hoàn tồn kho (Khai thác từ thông tin trong Order)
            Long ticketId = Long.valueOf(order.getTicketId());
            int quantity = order.getQuantity();
            oldStock = ticketOrderRepository.getStockAvailable(ticketId);   // Lấy oldStock
            log.info("Restoring stock: ticketId={}, quantity={}", ticketId, quantity);

            // Hoàn kho Database
            boolean isStockRecoveredDB = ticketOrderRepository.increaseStock(ticketId, quantity);
            if (!isStockRecoveredDB) {
                throw new RuntimeException("DB Stock recovery failed for order: " + orderNumber);
            }

            // Hoàn kho Redis
            boolean isStockRecoveredRedis = stockOrderCacheService.increaseStockCache(ticketId, quantity);
            if (!isStockRecoveredRedis) {
                log.warn("Redis stock recovery failed (Inconsistency), order: {}", orderNumber);

            }
            int newStock = oldStock + quantity;

// ==================== TẠO AUDIT LOG ====================
            OrderAuditLog auditLog = OrderAuditLog.createCancelOrderLog(
                    ticketId,
                    userId.intValue(),
                    order.getQuantity(),
                    oldStock,
                    newStock,
                    orderNumber
            );
            auditLogService.index(auditLog);   // Gọi async
            log.info("Cancel Order Successfully: {}", orderNumber);
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //
            lock.unlock();
        }
    }
}
