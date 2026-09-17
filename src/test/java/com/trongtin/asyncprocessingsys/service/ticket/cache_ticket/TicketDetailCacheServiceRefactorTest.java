package com.trongtin.asyncprocessingsys.service.ticket.cache_ticket;

import com.google.common.cache.Cache;
import com.trongtin.asyncprocessingsys.cache.distributed.RedisDistributedLocker;
import com.trongtin.asyncprocessingsys.cache.distributed.RedisDistributedService;
import com.trongtin.asyncprocessingsys.cache.redis.RedisInfrasService;
import com.trongtin.asyncprocessingsys.model.TicketDetailCache;
import com.trongtin.asyncprocessingsys.model.entity.TicketDetail;
import com.trongtin.asyncprocessingsys.service.ticket.TicketDetailDomainService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketDetailCacheServiceRefactorTest {
    @Mock
    private Cache<Long, TicketDetailCache> localCache;
    @Mock
    private RedisDistributedService redisDistributedService;
    @Mock
    private RedisInfrasService redisService;

    @Mock
    private TicketDetailDomainService domainService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RedisDistributedLocker locker;
    @InjectMocks
    private TicketDetailCacheServiceRefactor service;

    private TicketDetail buildTicket(Long id) {

        TicketDetail entity = new TicketDetail();

        entity.setId(id);
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

        return entity;
    }
    // case1 : local cache hit | Client -> local cache hit (gauve) -> return

    @Test
    void shouldReturnLocalCache_WhenLocalCacheHit() throws Exception {

        // ---------- Arrange ----------
        TicketDetail ticket = buildTicket(1L);

        TicketDetailCache localCache =
                new TicketDetailCache(100L, ticket);

        putLocalCache(1L, localCache);

        // ---------- Act ----------
        TicketDetailCache result =
                service.getTicketDetail(1L, null);

        // ---------- Assert ----------
        assertNotNull(result);
        assertSame(localCache, result);

//        verifyNoInteractions(redisInfrasService);
//        verifyNoInteractions(redisDistributedService);
//        verifyNoInteractions(ticketDetailDomainService);
    }
    @SuppressWarnings("unchecked")
    private void clearLocalCache() throws Exception {

        Field field =
                TicketDetailCacheServiceRefactor.class
                        .getDeclaredField("ticketDetailLocalCache");

        field.setAccessible(true);

        Cache<Long, TicketDetailCache> cache =
                (Cache<Long, TicketDetailCache>) field.get(null);

        cache.invalidateAll();
    }

    //Case 2
    // Local MISS
    // Redis HIT
    // Update Local Cache
    // Return

    @Test
    void shouldReturnRedisCacheAndUpdateLocalCache_WhenRedisHit() throws Exception {

        // ---------- Arrange ----------
        TicketDetail ticket = buildTicket(1L);

        TicketDetailCache redisCache =
                new TicketDetailCache(200L, ticket);

        when(redisService.getObject(
                anyString(),
                eq(TicketDetailCache.class)))
                .thenReturn(redisCache);

        // ---------- Act ----------
        TicketDetailCache result =
                service.getTicketDetail(1L, null);

        // ---------- Assert ----------
        assertNotNull(result);
        assertEquals(200L, result.getVersion());

        verify(redisService)
                .getObject(anyString(), eq(TicketDetailCache.class));

        verify(domainService, never())
                .getTicketDetailById(any());

        // verify Local Cache đã được update
        TicketDetailCache cacheInLocal =
                getLocalCache(1L);

        assertNotNull(cacheInLocal);
        assertEquals(200L, cacheInLocal.getVersion());
    }


    // case 3
    // Local MISS
    // Redis MISS
    // Lock Success
    // DB HIT
    // Update Redis
    // Update Local
    // Return

    @Test
    void shouldQueryDatabaseAndUpdateCaches_WhenRedisMissAndLockSuccess() throws Exception {

        // ---------- Arrange ----------

        TicketDetail ticket = buildTicket(1L);

        TicketDetailCache cache =
                new TicketDetailCache(1L, ticket);

        when(redisService.getObject(
                anyString(),
                eq(TicketDetailCache.class)))
                .thenReturn(null)     // lần đầu Redis MISS
                .thenReturn(cache);   // lần thứ hai sau setObject

        when(redisDistributedService.getDistributedLock(anyString()))
                .thenReturn(locker);

        when(locker.tryLock(anyLong(),
                anyLong(),
                any(TimeUnit.class)))
                .thenReturn(true);

        when(domainService.getTicketDetailById(1L))
                .thenReturn(ticket);

        // ---------- Act ----------

        TicketDetailCache result =
                service.getTicketDetail(1L, null);

        // ---------- Assert ----------

        assertNotNull(result);
        assertEquals(ticket.getId(),
                result.getTicketDetail().getId());

        verify(redisDistributedService)
                .getDistributedLock(anyString());

        verify(locker)
                .tryLock(anyLong(),
                        anyLong(),
                        any(TimeUnit.class));

        verify(domainService)
                .getTicketDetailById(1L);

        verify(redisService)
                .setObject(anyString(),
                        any(TicketDetailCache.class));

        verify(locker)
                .unlock();

        // Verify Local Cache đã có dữ liệu

        TicketDetailCache local =
                getLocalCache(1L);

        assertNotNull(local);
        assertEquals(ticket.getId(),
                local.getTicketDetail().getId());
    }

    @Test
    void shouldReturnNull_WhenDatabaseReturnsNull() throws InterruptedException {

        // Arrange
        when(redisService.getObject(anyString(), eq(TicketDetailCache.class)))
                .thenReturn(null)
                .thenReturn(null);

        when(redisDistributedService.getDistributedLock(anyString()))
                .thenReturn(locker);

        when(locker.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        when(domainService.getTicketDetailById(anyLong()))
                .thenReturn(null);

        // Act
        TicketDetailCache result = service.getTicketDetail(1L, null);

        // Assert
        assertNull(result);

        verify(domainService).getTicketDetailById(1L);

        verify(redisService, never())
                .setObject(anyString(), any());

        verify(locker).unlock();
    }

    //Local MISS → Redis MISS → Lock FAIL → Retry → Lock Success
    @Test
    @DisplayName("Flow 5 - Retry Until Lock Success")
    void shouldRetryWhenLockFailed() throws InterruptedException {

        TicketDetail ticket = buildTicket(1L);

        TicketDetailCache cache =
                new TicketDetailCache(1L, ticket);

        when(redisDistributedService.getDistributedLock(anyString()))
                .thenReturn(locker);

        when(locker.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false)
                .thenReturn(false)
                .thenReturn(true);

        when(redisService.getObject(anyString(), eq(TicketDetailCache.class)))
                .thenReturn(null)
                .thenReturn(null)
                .thenReturn(cache);

        TicketDetailCache result =
                service.getTicketDetailDatabase(1L);

        assertNotNull(result);

        verify(locker, times(3))
                .tryLock(anyLong(), anyLong(), any(TimeUnit.class));
    }

    //Retry 3 lần vẫn không lấy được Lock
    @Test
    @DisplayName("Flow 6 - Retry Exceeded")
    void shouldReturnNull_WhenRetryExceeded() throws InterruptedException {

        when(redisDistributedService.getDistributedLock(anyString()))
                .thenReturn(locker);

        when(locker.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false)
                .thenReturn(false)
                .thenReturn(false);

        when(redisService.getObject(anyString(), eq(TicketDetailCache.class)))
                .thenReturn(null);

        TicketDetailCache result =
                service.getTicketDetailDatabase(1L);

        assertNull(result);

        verify(domainService, never())
                .getTicketDetailById(anyLong());

        verify(locker, times(3))
                .tryLock(anyLong(), anyLong(), any(TimeUnit.class));
    }

    //Thread khác vừa build Cache

    @Test
    @DisplayName("Flow 7 - Cache Created By Another Thread")
    void shouldReturnCacheAfterRetry() throws InterruptedException {

        TicketDetail ticket = buildTicket(1L);

        TicketDetailCache cache =
                new TicketDetailCache(1L, ticket);

        when(redisDistributedService.getDistributedLock(anyString()))
                .thenReturn(locker);

        when(locker.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        when(redisService.getObject(anyString(), eq(TicketDetailCache.class)))
                .thenReturn(null)
                .thenReturn(cache);

        TicketDetailCache result =
                service.getTicketDetailDatabase(1L);

        assertNotNull(result);

        verify(domainService, never())
                .getTicketDetailById(anyLong());
    }

    // DB Throw Exception

    @Test
    @DisplayName("Flow 8 - Unlock When Exception")
    void shouldUnlockWhenDatabaseThrowException() throws InterruptedException {

        when(redisDistributedService.getDistributedLock(anyString()))
                .thenReturn(locker);

        when(locker.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        when(redisService.getObject(anyString(), eq(TicketDetailCache.class)))
                .thenReturn(null)
                .thenReturn(null);

        when(domainService.getTicketDetailById(anyLong()))
                .thenThrow(new RuntimeException("Database Error"));

        assertThrows(RuntimeException.class,
                () -> service.getTicketDetailDatabase(1L));

        verify(locker).unlock();
    }
    @SuppressWarnings("unchecked")
    private void putLocalCache(Long key,
                               TicketDetailCache value) throws Exception {

        Field field =
                TicketDetailCacheServiceRefactor.class
                        .getDeclaredField("ticketDetailLocalCache");

        field.setAccessible(true);

        Cache<Long, TicketDetailCache> cache =
                (Cache<Long, TicketDetailCache>) field.get(null);

        cache.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private TicketDetailCache getLocalCache(Long key) throws Exception {

        Field field =
                TicketDetailCacheServiceRefactor.class
                        .getDeclaredField("ticketDetailLocalCache");

        field.setAccessible(true);

        Cache<Long, TicketDetailCache> cache =
                (Cache<Long, TicketDetailCache>) field.get(null);

        return cache.getIfPresent(key);
    }
}