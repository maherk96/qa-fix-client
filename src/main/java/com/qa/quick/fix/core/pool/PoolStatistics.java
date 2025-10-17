package com.qa.quick.fix.core.pool;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Data
public class PoolStatistics {
    private final int totalClients;
    private final int connectedClients;
    private final long totalMessagesSent;
    private final long totalMessagesReceived;
    private final Map<String, Long> clientMessageCounts;

    public PoolStatistics(
            int totalClients,
            int connectedClients,
            long totalMessagesSent,
            long totalMessagesReceived,
            Map<String, AtomicLong> clientMessageCounts) {
        this.totalClients = totalClients;
        this.connectedClients = connectedClients;
        this.totalMessagesSent = totalMessagesSent;
        this.totalMessagesReceived = totalMessagesReceived;
        this.clientMessageCounts = new HashMap<>();
        clientMessageCounts.forEach((k, v) -> this.clientMessageCounts.put(k, v.get()));
    }

    public double getConnectionRate() {
        return totalClients > 0 ? (double) connectedClients / totalClients : 0.0;
    }
}