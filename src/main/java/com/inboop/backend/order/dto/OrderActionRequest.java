package com.inboop.backend.order.dto;

import com.inboop.backend.order.enums.PaymentStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTOs for order actions.
 */
public class OrderActionRequest {

    /**
     * Request for updating order items.
     */
    public static class UpdateItemsRequest {
        private List<ItemRequest> items;

        public List<ItemRequest> getItems() {
            return items;
        }

        public void setItems(List<ItemRequest> items) {
            this.items = items;
        }

        public static class ItemRequest {
            private String name;
            private Integer quantity;
            private BigDecimal unitPrice;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public Integer getQuantity() {
                return quantity;
            }

            public void setQuantity(Integer quantity) {
                this.quantity = quantity;
            }

            public BigDecimal getUnitPrice() {
                return unitPrice;
            }

            public void setUnitPrice(BigDecimal unitPrice) {
                this.unitPrice = unitPrice;
            }
        }
    }

    /**
     * Request for updating shipping details.
     */
    public static class UpdateShippingRequest {
        private AddressRequest address;
        private TrackingRequest tracking;

        public AddressRequest getAddress() {
            return address;
        }

        public void setAddress(AddressRequest address) {
            this.address = address;
        }

        public TrackingRequest getTracking() {
            return tracking;
        }

        public void setTracking(TrackingRequest tracking) {
            this.tracking = tracking;
        }

        public static class AddressRequest {
            private String line1;
            private String line2;
            private String city;
            private String state;
            private String postalCode;
            private String country;

            public String getLine1() {
                return line1;
            }

            public void setLine1(String line1) {
                this.line1 = line1;
            }

            public String getLine2() {
                return line2;
            }

            public void setLine2(String line2) {
                this.line2 = line2;
            }

            public String getCity() {
                return city;
            }

            public void setCity(String city) {
                this.city = city;
            }

            public String getState() {
                return state;
            }

            public void setState(String state) {
                this.state = state;
            }

            public String getPostalCode() {
                return postalCode;
            }

            public void setPostalCode(String postalCode) {
                this.postalCode = postalCode;
            }

            public String getCountry() {
                return country;
            }

            public void setCountry(String country) {
                this.country = country;
            }
        }

        public static class TrackingRequest {
            private String carrier;
            private String trackingId;
            private String trackingUrl;

            public String getCarrier() {
                return carrier;
            }

            public void setCarrier(String carrier) {
                this.carrier = carrier;
            }

            public String getTrackingId() {
                return trackingId;
            }

            public void setTrackingId(String trackingId) {
                this.trackingId = trackingId;
            }

            public String getTrackingUrl() {
                return trackingUrl;
            }

            public void setTrackingUrl(String trackingUrl) {
                this.trackingUrl = trackingUrl;
            }
        }
    }

    /**
     * Request for updating payment status.
     */
    public static class UpdatePaymentStatusRequest {
        private PaymentStatus paymentStatus;

        public PaymentStatus getPaymentStatus() {
            return paymentStatus;
        }

        public void setPaymentStatus(PaymentStatus paymentStatus) {
            this.paymentStatus = paymentStatus;
        }
    }

    /**
     * Request for assigning an order.
     */
    public static class AssignOrderRequest {
        private Long assignedToUserId;

        public Long getAssignedToUserId() {
            return assignedToUserId;
        }

        public void setAssignedToUserId(Long assignedToUserId) {
            this.assignedToUserId = assignedToUserId;
        }
    }

    /**
     * Request for shipping an order (includes tracking info).
     */
    public static class ShipOrderRequest {
        private String carrier;
        private String trackingNumber;
        private String trackingUrl;
        private String note;

        public String getCarrier() {
            return carrier;
        }

        public void setCarrier(String carrier) {
            this.carrier = carrier;
        }

        public String getTrackingNumber() {
            return trackingNumber;
        }

        public void setTrackingNumber(String trackingNumber) {
            this.trackingNumber = trackingNumber;
        }

        public String getTrackingUrl() {
            return trackingUrl;
        }

        public void setTrackingUrl(String trackingUrl) {
            this.trackingUrl = trackingUrl;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    /**
     * Request for cancelling an order.
     */
    public static class CancelOrderRequest {
        private String reason;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    /**
     * Request for refunding an order.
     */
    public static class RefundOrderRequest {
        private String reason;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
