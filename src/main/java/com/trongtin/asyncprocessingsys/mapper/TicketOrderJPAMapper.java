package com.trongtin.asyncprocessingsys.mapper;

import com.trongtin.asyncprocessingsys.model.entity.TicketDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface TicketOrderJPAMapper extends JpaRepository<TicketDetail, Long> {

    /**
     * Retrieves the available stock for a given ticket.
     *
     * @param ticketId The ID of the ticket item.
     * @return The available stock quantity.
     */
    @Query("SELECT t.stockAvailable FROM TicketDetail t WHERE t.id = :ticketId")
    int getStockAvailable(@Param("ticketId") Long ticketId);


    /**
     * Decreases stock if there is enough available stock.
     * Ensures that stock is only deducted when the available quantity is sufficient.
     * Prevents overselling in high-concurrency scenarios (basic)
     *
     * @param ticketId   The ID of the ticket item.
     * @param quantity The quantity to decrease.
     * @return The number of rows affected (should be 0 or 1).
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE TicketDetail t SET t.updatedAt = CURRENT_TIMESTAMP, " +
            "t.stockAvailable = t.stockAvailable - :quantity " +
            "WHERE t.id = :ticketId AND t.stockAvailable >= :quantity")
    int decreaseStock1(@Param("ticketId") Long ticketId, @Param("quantity") int quantity);



    @Modifying
    @Transactional
    @Query("UPDATE TicketDetail t SET t.updatedAt = CURRENT_TIMESTAMP, " +
            "t.stockAvailable = :oldStockAvailable - :quantity " +
            "WHERE t.id = :ticketId AND t.stockAvailable = :oldStockAvailable")
    int decreaseStockCAS(@Param("ticketId") Long ticketId, @Param("oldStockAvailable") int oldStockAvailable, @Param("quantity") int quantity);

    /**
     *  Hoàn kho và Cập nhật trạng thái trong Database
     */
    @Modifying
    @Transactional
    @Query("UPDATE TicketDetail t SET t.updatedAt = CURRENT_TIMESTAMP, " +
            "t.stockAvailable = t.stockAvailable + :quantity " +
            "WHERE t.id = :ticketId")
    int increaseStock(@Param("ticketId") Long ticketId, @Param("quantity") int quantity);
}
