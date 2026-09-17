package com.trongtin.asyncprocessingsys.service.ticket.impl;

import com.trongtin.asyncprocessingsys.dto.response.TicketDetailDTO;
import com.trongtin.asyncprocessingsys.model.TicketDetailCache;
import com.trongtin.asyncprocessingsys.model.entity.TicketDetail;
import com.trongtin.asyncprocessingsys.service.ticket.cache_ticket.TicketDetailCacheServiceRefactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class TicketDetailServiceImplTest {

    @Mock
    private TicketDetailCacheServiceRefactor cacheService;

    @InjectMocks
    TicketDetailServiceImpl ticketDetailServiceIpml;


    @Test
    void should_return_ticket_detail() {
        Long ticketId = 1L;
        Long version = 100L;


        TicketDetail entity = new TicketDetail();

        entity.setId(1L);
        entity.setName("Concert");

        entity.setStockInitial(100);
        entity.setStockAvailable(80);

        entity.setStockPrepared(true);

        entity.setPriceOriginal(BigDecimal.valueOf(500000));
        entity.setPriceFlash(BigDecimal.valueOf(300000));

        entity.setSaleStartTime(LocalDateTime.now());
        entity.setSaleEndTime(LocalDateTime.now().plusDays(1));

        entity.setStatus(0);
        entity.setActivityId(10L);

        TicketDetailCache cache = new TicketDetailCache();
        cache.setTicketDetail(entity);
        cache.setVersion(version);

        when(cacheService.getTicketDetail(1L,version))
                .thenReturn(cache);

        TicketDetailDTO dto =
                ticketDetailServiceIpml.getTicketDetailById(1L,version);
        verify(cacheService)
                .getTicketDetail(ticketId, version);
    }

    @Test
    void should_order_successfully() {

        Long ticketId = 1L;

        when(cacheService.orderTicketByUser(ticketId))
                .thenReturn(true);

        boolean result =
                cacheService.orderTicketByUser(ticketId);

        assertTrue(result);

        verify(cacheService)
                .orderTicketByUser(ticketId);
    }
}