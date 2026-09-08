package io.pockethive.worker.sdk.auth;

/**
 * Responsibility: define the AuthProfileRefresh contract.
 * Must not: select infrastructure clients or own adapter lifecycle.
 * Contract: RESP-AUTH-VALUES — docs/architecture/runtime-responsibilities.md#resp-auth-values.
 */
public final class AuthProfileRefresh {
    private int refreshAheadSeconds = 60;
    private int emergencyRefreshAheadSeconds = 10;
    private int leaseSeconds = 15;

    public int getRefreshAheadSeconds() {
        return refreshAheadSeconds;
    }

    public void setRefreshAheadSeconds(int refreshAheadSeconds) {
        this.refreshAheadSeconds = Math.max(0, refreshAheadSeconds);
    }

    public int getEmergencyRefreshAheadSeconds() {
        return emergencyRefreshAheadSeconds;
    }

    public void setEmergencyRefreshAheadSeconds(int emergencyRefreshAheadSeconds) {
        this.emergencyRefreshAheadSeconds = Math.max(0, emergencyRefreshAheadSeconds);
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds <= 0 ? 15 : leaseSeconds;
    }
}
