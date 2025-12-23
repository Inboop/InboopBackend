package com.inboop.backend.workspace.enums;

/**
 * Available subscription plans.
 */
public enum PlanType {
    PRO(5),           // Pro plan: max 5 users
    ENTERPRISE(100);  // Enterprise: up to 100 users

    private final int maxUsers;

    PlanType(int maxUsers) {
        this.maxUsers = maxUsers;
    }

    public int getMaxUsers() {
        return maxUsers;
    }
}
