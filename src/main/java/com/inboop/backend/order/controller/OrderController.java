package com.inboop.backend.order.controller;

import com.inboop.backend.auth.entity.User;
import com.inboop.backend.auth.repository.UserRepository;
import com.inboop.backend.lead.enums.ChannelType;
import com.inboop.backend.order.dto.*;
import com.inboop.backend.order.enums.OrderStatus;
import com.inboop.backend.order.enums.PaymentMethod;
import com.inboop.backend.order.enums.PaymentStatus;
import com.inboop.backend.order.service.OrderService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * REST controller for order management.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;
    private final UserRepository userRepository;

    public OrderController(OrderService orderService, UserRepository userRepository) {
        this.orderService = orderService;
        this.userRepository = userRepository;
    }

    /**
     * List orders with filtering and pagination.
     * GET /api/orders
     */
    @GetMapping
    public ResponseEntity<PagedOrderResponse> listOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) ChannelType channel,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(defaultValue = "createdAt_desc") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        User currentUser = getCurrentUser();
        // TODO: Get businessId from user's active business context
        Long businessId = null; // For now, return all orders (demo mode)

        PagedOrderResponse response = orderService.listOrders(
                businessId,
                currentUser != null ? currentUser.getId() : null,
                status,
                paymentStatus,
                paymentMethod,
                channel,
                assignedTo,
                q,
                createdFrom,
                createdTo,
                sort,
                page,
                pageSize
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Create a new order.
     * POST /api/v1/orders
     */
    @PostMapping
    public ResponseEntity<OrderDetailDto> createOrder(@RequestBody CreateOrderRequest request) {
        User currentUser = getCurrentUser();
        OrderDetailDto order = orderService.createOrder(request, currentUser);
        return ResponseEntity.ok(order);
    }

    /**
     * Get order detail.
     * GET /api/orders/{orderId}
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDetailDto> getOrder(@PathVariable Long orderId) {
        OrderDetailDto order = orderService.getOrderDetail(orderId);
        return ResponseEntity.ok(order);
    }

    /**
     * Confirm an order.
     * POST /api/orders/{orderId}/confirm
     */
    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<OrderDetailDto> confirmOrder(@PathVariable Long orderId) {
        User currentUser = getCurrentUser();
        OrderDetailDto order = orderService.confirmOrder(orderId, currentUser);
        return ResponseEntity.ok(order);
    }

    /**
     * Ship an order.
     * POST /api/orders/{orderId}/ship
     */
    @PostMapping("/{orderId}/ship")
    public ResponseEntity<OrderDetailDto> shipOrder(
            @PathVariable Long orderId,
            @RequestBody(required = false) OrderActionRequest.ShipOrderRequest request
    ) {
        User currentUser = getCurrentUser();
        OrderDetailDto order = orderService.shipOrder(orderId, request, currentUser);
        return ResponseEntity.ok(order);
    }

    /**
     * Mark order as delivered.
     * POST /api/orders/{orderId}/deliver
     */
    @PostMapping("/{orderId}/deliver")
    public ResponseEntity<OrderDetailDto> deliverOrder(@PathVariable Long orderId) {
        User currentUser = getCurrentUser();
        OrderDetailDto order = orderService.deliverOrder(orderId, currentUser);
        return ResponseEntity.ok(order);
    }

    /**
     * Cancel an order.
     * POST /api/orders/{orderId}/cancel
     */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderDetailDto> cancelOrder(
            @PathVariable Long orderId,
            @RequestBody(required = false) OrderActionRequest.CancelOrderRequest request
    ) {
        User currentUser = getCurrentUser();
        OrderDetailDto order = orderService.cancelOrder(orderId, request, currentUser);
        return ResponseEntity.ok(order);
    }

    /**
     * Refund an order.
     * POST /api/orders/{orderId}/refund
     */
    @PostMapping("/{orderId}/refund")
    public ResponseEntity<OrderDetailDto> refundOrder(
            @PathVariable Long orderId,
            @RequestBody(required = false) OrderActionRequest.RefundOrderRequest request
    ) {
        User currentUser = getCurrentUser();
        OrderDetailDto order = orderService.refundOrder(orderId, request, currentUser);
        return ResponseEntity.ok(order);
    }

    /**
     * Update payment status.
     * PATCH /api/orders/{orderId}/payment-status
     */
    @PatchMapping("/{orderId}/payment-status")
    public ResponseEntity<OrderDetailDto> updatePaymentStatus(
            @PathVariable Long orderId,
            @RequestBody OrderActionRequest.UpdatePaymentStatusRequest request
    ) {
        User currentUser = getCurrentUser();
        OrderDetailDto order = orderService.updatePaymentStatus(orderId, request.getPaymentStatus(), currentUser);
        return ResponseEntity.ok(order);
    }

    /**
     * Assign order to a user.
     * PATCH /api/orders/{orderId}/assign
     */
    @PatchMapping("/{orderId}/assign")
    public ResponseEntity<OrderDetailDto> assignOrder(
            @PathVariable Long orderId,
            @RequestBody OrderActionRequest.AssignOrderRequest request
    ) {
        User currentUser = getCurrentUser();
        OrderDetailDto order = orderService.assignOrder(orderId, request.getAssignedToUserId(), currentUser);
        return ResponseEntity.ok(order);
    }

    /**
     * Update order items.
     * PATCH /api/v1/orders/{orderId}/items
     */
    @PatchMapping("/{orderId}/items")
    public ResponseEntity<OrderDetailDto> updateOrderItems(
            @PathVariable Long orderId,
            @RequestBody OrderActionRequest.UpdateItemsRequest request
    ) {
        User currentUser = getCurrentUser();
        OrderDetailDto order = orderService.updateOrderItems(orderId, request, currentUser);
        return ResponseEntity.ok(order);
    }

    /**
     * Update shipping details.
     * PATCH /api/v1/orders/{orderId}/shipping
     */
    @PatchMapping("/{orderId}/shipping")
    public ResponseEntity<OrderDetailDto> updateOrderShipping(
            @PathVariable Long orderId,
            @RequestBody OrderActionRequest.UpdateShippingRequest request
    ) {
        User currentUser = getCurrentUser();
        OrderDetailDto order = orderService.updateOrderShipping(orderId, request, currentUser);
        return ResponseEntity.ok(order);
    }

    /**
     * Get the current authenticated user.
     */
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        String email = authentication.getName();
        return userRepository.findByEmail(email).orElse(null);
    }
}
