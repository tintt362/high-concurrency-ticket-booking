package com.trongtin.asyncprocessingsys.service.order.impl;

import com.trongtin.asyncprocessingsys.dto.response.PlaceOrderResponse;
import com.trongtin.asyncprocessingsys.model.entity.TickerOrder;
import com.trongtin.asyncprocessingsys.repository.ticket.TicketOrderRepository;
import com.trongtin.asyncprocessingsys.service.order.OrderDeductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderTransactionService {

    private final TicketOrderRepository ticketOrderRepository;
    private final OrderDeductionService orderDeductionService;

    @Transactional(rollbackFor = Exception.class)
    public  PlaceOrderResponse createOrder(
            Long ticketId,
            int quantity,
            long unitPrice
    ) {

        // =====================================================
        // 1. UPDATE persistent stock
        // =====================================================

        boolean affectedRows =
                ticketOrderRepository.decreaseStock1(
                        ticketId,
                        quantity
                );

        if (affectedRows != true) {
//
//            throw new StockConflictException(
//                    "DB stock update failed"
          //  );
        }

        // =====================================================
        // 2. Generate order information
        // =====================================================

        int userId =
                ThreadLocalRandom.current()
                        .nextInt(1, 10);

        String orderNumber =
                "OKX-SGN-"
                        + userId
                        + "-"
                        + UUID.randomUUID();

        String tableName =
                LocalDate.now()
                        .format(
                                DateTimeFormatter.ofPattern(
                                        "yyyyMM"
                                )
                        );

        // =====================================================
        // 3. INSERT ORDER
        // =====================================================

        TickerOrder order = new TickerOrder();

        order.setTicketId(ticketId.intValue());
        order.setQuantity(quantity);
        order.setOrderStatus(0);
        order.setUserId(userId);
        order.setOrderNumber(orderNumber);

        order.setTotalAmount(
                BigDecimal.valueOf(unitPrice)
                        .multiply(
                                BigDecimal.valueOf(quantity)
                        )
        );

        order.setTerminalId("OKX-SGN");
        order.setOrderNotes("Order -> Pending");

        orderDeductionService.insertOrder(
                tableName,
                order
        );

        // =====================================================
        // 4. Transaction chưa commit ở đây!
        //
        // Spring sẽ COMMIT khi method này return bình thường.
        // =====================================================

        log.info(
                "createOrder: DB operations completed, " +
                        "ticketId={}, orderNumber={}",
                ticketId,
                orderNumber
        );

        return PlaceOrderResponse.success(
                orderNumber
        );
    }
}