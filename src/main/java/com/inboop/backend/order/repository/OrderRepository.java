package com.inboop.backend.order.repository;

import com.inboop.backend.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByBusinessId(Long businessId);

    List<Order> findByLeadId(Long leadId);

    /**
     * Find all orders for a specific Instagram Business Account.
     */
    @Query("SELECT o FROM Order o WHERE o.business.instagramBusinessId = :instagramBusinessId")
    List<Order> findAllByBusinessInstagramBusinessId(@Param("instagramBusinessId") String instagramBusinessId);

    /**
     * Count orders for a business.
     */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.business.instagramBusinessId = :instagramBusinessId")
    long countByBusinessInstagramBusinessId(@Param("instagramBusinessId") String instagramBusinessId);

    /**
     * Anonymize order customer data for GDPR compliance.
     *
     * META APP REVIEW NOTE:
     * Orders have legal/financial significance and may need to be retained for accounting.
     * Instead of deleting orders, we anonymize customer PII:
     * - customer_name -> 'DELETED'
     * - customer_phone -> null
     * - customer_address -> null
     * - lead_id -> null (break link to deleted lead)
     *
     * This approach:
     * - Maintains order records for business reporting and accounting
     * - Removes all personally identifiable information
     * - Complies with data deletion requirements while preserving aggregated analytics
     */
    @Modifying
    @Query("UPDATE Order o SET o.customerName = 'DELETED', o.customerPhone = null, " +
           "o.customerAddress = null, o.lead = null " +
           "WHERE o.business.instagramBusinessId = :instagramBusinessId")
    int anonymizeByBusinessInstagramBusinessId(@Param("instagramBusinessId") String instagramBusinessId);
}
