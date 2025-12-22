package com.inboop.backend.order.enums;

/**
 * Types of timeline events for orders.
 */
public enum TimelineEventType {
    STATUS_CHANGE,      // Order status changed
    PAYMENT_UPDATE,     // Payment status changed
    ITEMS_UPDATED,      // Order items were updated
    SHIPPING_UPDATED,   // Shipping details were updated
    ASSIGNMENT_CHANGE,  // Order assignment changed
    NOTE_ADDED          // General note added
}
