package com.qa.quick.fix.poc.pool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.quick.fix.poc.client.ClientStatus;
import com.qa.quick.fix.poc.client.FixClientPoolException;
import com.qa.quick.fix.poc.client.ManagedFixClient;
import com.qa.quick.fix.poc.client.MessageListener;
import com.qa.quick.fix.poc.client.SessionEventListener;
import com.qa.quick.fix.poc.config.ClientDefinition;
import com.qa.quick.fix.poc.config.ClientPortInfo;
import com.qa.quick.fix.poc.config.ConnectionEnvironment;
import com.qa.quick.fix.poc.config.FixClientConfiguration;
import com.qa.quick.fix.poc.config.PortsConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Message;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages a pool of FIX clients based on JSON configuration
 * Supports both trade-only and trade+quote client types
 */
public class FixClientPoolManager {
    private static final Logger logger = LoggerFactory.getLogger(FixClientPoolManager.class);

    private final FixClientConfiguration config;
    private final PortsConfiguration portsConfig;
    private final String environmentName;
    private final Set<String> clientStreamNames;

    // Client pool management
    private final Map<String, ManagedFixClient> clientPool = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final AtomicBoolean isStarted = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> healthCheckTask;

    // Global listeners
    private MessageListener globalMessageListener;
    private SessionEventListener globalSessionEventListener;

    // Metrics
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final Map<String, AtomicLong> clientMessageCounts = new ConcurrentHashMap<>();

    /**
     * Represents the result of starting the client pool
     */
    public static class StartupResult {
        private final Set<String> successfulClients;
        private final Map<String, Exception> failedClients;
        private final boolean allSuccessful;

        public StartupResult(Set<String> successfulClients, Map<String, Exception> failedClients, boolean allSuccessful) {
            this.successfulClients = new HashSet<>(successfulClients);
            this.failedClients = new HashMap<>(failedClients);
            this.allSuccessful = allSuccessful;
        }

        public Set<String> getSuccessfulClients() { return successfulClients; }
        public Map<String, Exception> getFailedClients() { return failedClients; }
        public boolean isAllSuccessful() { return allSuccessful; }
        public int getSuccessfulCount() { return successfulClients.size(); }
        public int getFailedCount() { return failedClients.size(); }
    }

    /**
     * Pool statistics for monitoring
     */
    public static class PoolStatistics {
        private final int totalClients;
        private final int connectedClients;
        private final long totalMessagesSent;
        private final long totalMessagesReceived;
        private final Map<String, Long> clientMessageCounts;

        public PoolStatistics(int totalClients, int connectedClients, long totalMessagesSent,
                              long totalMessagesReceived, Map<String, AtomicLong> clientMessageCounts) {
            this.totalClients = totalClients;
            this.connectedClients = connectedClients;
            this.totalMessagesSent = totalMessagesSent;
            this.totalMessagesReceived = totalMessagesReceived;
            this.clientMessageCounts = new HashMap<>();
            clientMessageCounts.forEach((k, v) -> this.clientMessageCounts.put(k, v.get()));
        }

        public int getTotalClients() { return totalClients; }
        public int getConnectedClients() { return connectedClients; }
        public long getTotalMessagesSent() { return totalMessagesSent; }
        public long getTotalMessagesReceived() { return totalMessagesReceived; }
        public Map<String, Long> getClientMessageCounts() { return clientMessageCounts; }
        public double getConnectionRate() { return totalClients > 0 ? (double) connectedClients / totalClients : 0.0; }
    }

    /**
     * Create pool manager from JSON configurations
     *
     * @param configPath Path to main JSON configuration file
     * @param portsConfigPath Path to ports JSON configuration file (can be null for trade-only clients)
     * @param environmentName Environment to connect to (e.g., "EMEA", "ALGO_UAT")
     * @param clientStreamNames Set of client stream names to connect
     */
    public FixClientPoolManager(String configPath, String portsConfigPath,
                                String environmentName, Set<String> clientStreamNames)
            throws IOException, FixClientPoolException {
        this.environmentName = environmentName;
        this.clientStreamNames = new HashSet<>(clientStreamNames);
        this.config = loadConfiguration(configPath);
        this.portsConfig = portsConfigPath != null ? loadPortsConfiguration(portsConfigPath) : null;

        validateConfiguration();

        // Initialize message counters for each client
        clientStreamNames.forEach(name -> clientMessageCounts.put(name, new AtomicLong(0)));

        logger.info("Pool manager initialized for environment {} with clients {}",
                environmentName, clientStreamNames);
    }

    /**
     * Create pool manager for trade-only clients (no separate ports config needed)
     *
     * @param configPath Path to main JSON configuration file
     * @param environmentName Environment to connect to (e.g., "ALGO_UAT")
     * @param clientStreamNames Set of client stream names to connect
     */
    public FixClientPoolManager(String configPath, String environmentName, Set<String> clientStreamNames)
            throws IOException, FixClientPoolException {
        this(configPath, null, environmentName, clientStreamNames);
    }

    /**
     * Start all configured clients
     */
    public void startAll() throws FixClientPoolException {
        StartupResult result = startAll(false);
        if (!result.isAllSuccessful()) {
            throw new FixClientPoolException("Failed to start all clients: " + result.getFailedClients().keySet());
        }
    }

    /**
     * Start all configured clients with option for partial success
     *
     * @param allowPartialSuccess If true, pool will start even if some clients fail
     * @return StartupResult containing success/failure details
     */
    public StartupResult startAll(boolean allowPartialSuccess) throws FixClientPoolException {
        if (isStarted.get()) {
            throw new FixClientPoolException("Pool already started");
        }

        logger.info("Starting FIX client pool...");

        Set<String> successful = new HashSet<>();
        Map<String, Exception> failed = new HashMap<>();

        try {
            // Start all clients, tracking successes and failures
            for (String clientStreamName : clientStreamNames) {
                try {
                    ManagedFixClient client = createManagedClient(clientStreamName);
                    client.start();
                    clientPool.put(clientStreamName, client);
                    successful.add(clientStreamName);
                    logger.info("Started client: {}", clientStreamName);
                } catch (Exception e) {
                    logger.error("Failed to start client: {}", clientStreamName, e);
                    failed.put(clientStreamName, e);
                }
            }

            if (successful.isEmpty()) {
                throw new FixClientPoolException("No clients started successfully");
            }

            // Wait for successful clients to connect (in parallel)
            waitForClients(successful);

            boolean allSuccessful = failed.isEmpty();
            if (!allSuccessful && !allowPartialSuccess) {
                stopAll();
                throw new FixClientPoolException("Failed to start all clients: " + failed.keySet());
            }

            isStarted.set(true);
            logger.info("Client pool started - Successful: {}, Failed: {}",
                    successful.size(), failed.size());

            return new StartupResult(successful, failed, allSuccessful);

        } catch (Exception e) {
            // Cleanup on failure
            forceStop();
            throw e instanceof FixClientPoolException ? (FixClientPoolException) e :
                    new FixClientPoolException("Failed to start client pool", e);
        }
    }

    /**
     * Stop all clients gracefully with default timeout
     */
    public void stopAll() {
        stopAll(30, TimeUnit.SECONDS);
    }

    /**
     * Stop all clients gracefully with specified timeout
     *
     * @param timeout Maximum time to wait for graceful shutdown
     * @param unit Time unit for timeout
     * @return true if all clients stopped gracefully, false if timeout occurred
     */
    public boolean stopAll(long timeout, TimeUnit unit) {
        logger.info("Stopping FIX client pool with timeout: {} {}", timeout, unit);

        long startTime = System.nanoTime();
        long timeoutNanos = unit.toNanos(timeout);

        // Cancel health monitoring
        if (healthCheckTask != null) {
            healthCheckTask.cancel(false);
            healthCheckTask = null;
        }

        // Stop all clients in parallel
        List<CompletableFuture<Void>> stopFutures = new ArrayList<>();

        for (ManagedFixClient client : clientPool.values()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    client.stop();
                    logger.debug("Stopped client: {}", client.getClientStreamName());
                } catch (Exception e) {
                    logger.error("Error stopping client {}", client.getClientStreamName(), e);
                }
            });
            stopFutures.add(future);
        }

        try {
            long remainingNanos = timeoutNanos - (System.nanoTime() - startTime);
            CompletableFuture.allOf(stopFutures.toArray(new CompletableFuture[0]))
                    .get(Math.max(1, remainingNanos), TimeUnit.NANOSECONDS);

            clientPool.clear();
            shutdownScheduler();

            isStarted.set(false);
            logger.info("FIX client pool stopped successfully");
            return true;

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.error("Timeout during graceful shutdown, forcing stop", e);
            forceStop();
            return false;
        }
    }

    /**
     * Enable health monitoring with specified interval
     *
     * @param intervalSeconds Interval between health checks in seconds
     */
    public void enableHealthMonitoring(long intervalSeconds) {
        if (healthCheckTask != null) {
            healthCheckTask.cancel(false);
        }

        healthCheckTask = scheduler.scheduleAtFixedRate(
                this::performHealthCheck,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );

        logger.info("Health monitoring enabled with interval: {} seconds", intervalSeconds);
    }

    /**
     * Disable health monitoring
     */
    public void disableHealthMonitoring() {
        if (healthCheckTask != null) {
            healthCheckTask.cancel(false);
            healthCheckTask = null;
            logger.info("Health monitoring disabled");
        }
    }

    /**
     * Send message to a specific client's trade session
     */
    public void sendTradeMessage(String clientStreamName, Message message) throws FixClientPoolException {
        ensureStarted();
        ManagedFixClient client = getClient(clientStreamName);
        client.sendTradeMessage(message);

        messagesSent.incrementAndGet();
        clientMessageCounts.get(clientStreamName).incrementAndGet();
    }

    /**
     * Send message to a specific client's quote session (if available)
     */
    public void sendQuoteMessage(String clientStreamName, Message message) throws FixClientPoolException {
        ensureStarted();
        ManagedFixClient client = getClient(clientStreamName);
        client.sendQuoteMessage(message);

        messagesSent.incrementAndGet();
        clientMessageCounts.get(clientStreamName).incrementAndGet();
    }

    /**
     * Get client status
     */
    public ClientStatus getClientStatus(String clientStreamName) {
        ManagedFixClient client = clientPool.get(clientStreamName);
        return client != null ? client.getStatus() : null;
    }

    /**
     * Get all client statuses
     */
    public Map<String, ClientStatus> getAllClientStatuses() {
        Map<String, ClientStatus> statuses = new HashMap<>();
        for (Map.Entry<String, ManagedFixClient> entry : clientPool.entrySet()) {
            statuses.put(entry.getKey(), entry.getValue().getStatus());
        }
        return statuses;
    }

    /**
     * Check if a specific client is connected (trade session)
     */
    public boolean isClientConnected(String clientStreamName) {
        ManagedFixClient client = clientPool.get(clientStreamName);
        return client != null && client.isTradeSessionConnected();
    }

    /**
     * Check if a specific client has quote session available
     */
    public boolean hasQuoteSession(String clientStreamName) {
        ManagedFixClient client = clientPool.get(clientStreamName);
        return client != null && client.hasQuoteSession();
    }

    /**
     * Check if a specific client is fully connected (all configured sessions)
     */
    public boolean isClientFullyConnected(String clientStreamName) {
        ManagedFixClient client = clientPool.get(clientStreamName);
        return client != null && client.isFullyConnected();
    }

    /**
     * Get available clients
     */
    public Set<String> getAvailableClients() {
        return new HashSet<>(clientPool.keySet());
    }

    /**
     * Get pool statistics
     */
    public PoolStatistics getStatistics() {
        int connectedCount = (int) clientPool.values().stream()
                .filter(ManagedFixClient::isFullyConnected)
                .count();

        return new PoolStatistics(
                clientPool.size(),
                connectedCount,
                messagesSent.get(),
                messagesReceived.get(),
                clientMessageCounts
        );
    }

    /**
     * Check if the pool is started
     */
    public boolean isStarted() {
        return isStarted.get();
    }

    // Listener setters
    public void setGlobalMessageListener(MessageListener listener) {
        this.globalMessageListener = listener;
    }

    public void setGlobalSessionEventListener(SessionEventListener listener) {
        this.globalSessionEventListener = listener;
    }

    // Private methods
    private void ensureStarted() throws FixClientPoolException {
        if (!isStarted.get()) {
            throw new FixClientPoolException("Pool is not started");
        }
    }

    private FixClientConfiguration loadConfiguration(String configPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();

        // Try to load from classpath first (for resources), then from file system
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(configPath)) {
            if (inputStream != null) {
                return mapper.readValue(inputStream, FixClientConfiguration.class);
            }
        }

        return mapper.readValue(new File(configPath), FixClientConfiguration.class);
    }

    private PortsConfiguration loadPortsConfiguration(String portsConfigPath) throws IOException {
        if (portsConfigPath == null) {
            return null;
        }

        ObjectMapper mapper = new ObjectMapper();

        // Try to load from classpath first (for resources), then from file system
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(portsConfigPath)) {
            if (inputStream != null) {
                return mapper.readValue(inputStream, PortsConfiguration.class);
            }
        }

        return mapper.readValue(new File(portsConfigPath), PortsConfiguration.class);
    }

    private void validateConfiguration() throws FixClientPoolException {
        // Check environment exists
        if (!config.getConnections().containsKey(environmentName)) {
            throw new FixClientPoolException("Environment not found: " + environmentName);
        }

        ConnectionEnvironment env = config.getConnections().get(environmentName);
        Set<String> usedPorts = new HashSet<>();

        // Check all client stream names exist and validate their configuration
        for (String clientStreamName : clientStreamNames) {
            if (!config.getClients().containsKey(clientStreamName)) {
                throw new FixClientPoolException("Client not found: " + clientStreamName);
            }

            ClientDefinition clientDef = config.getClients().get(clientStreamName);

            // Validate at least one session is configured
            if (clientDef.getTradeSession() == null && clientDef.getQuoteSession() == null) {
                throw new FixClientPoolException("Client " + clientStreamName + " has no sessions configured");
            }

            // Check for port conflicts
            validatePortUsage(clientDef, usedPorts);

            // Validate required connection details exist
            if (clientDef.getTradeSession() != null && env.getTrade() == null) {
                throw new FixClientPoolException("Client " + clientStreamName + " needs trade session but no trade connection configured");
            }

            if (clientDef.getQuoteSession() != null && env.getQuote() == null) {
                throw new FixClientPoolException("Client " + clientStreamName + " needs quote session but no quote connection configured");
            }
        }

        logger.info("Configuration validated successfully");
    }

    private void validatePortUsage(ClientDefinition clientDef, Set<String> usedPorts) throws FixClientPoolException {
        if (portsConfig == null || portsConfig.getClients() == null) {
            return; // No port validation if no ports config
        }

        for (ClientPortInfo portInfo : portsConfig.getClients()) {
            String senderCompId = null;

            if (clientDef.getTradeSession() != null &&
                    portInfo.getName().equals(clientDef.getTradeSession().getSenderCompID())) {
                senderCompId = clientDef.getTradeSession().getSenderCompID();
            } else if (clientDef.getQuoteSession() != null &&
                    portInfo.getName().equals(clientDef.getQuoteSession().getSenderCompID())) {
                senderCompId = clientDef.getQuoteSession().getSenderCompID();
            }

            if (senderCompId != null) {
                String portKey = portInfo.getLocation() + ":" + portInfo.getPort();
                if (usedPorts.contains(portKey)) {
                    throw new FixClientPoolException("Port conflict: " + portKey + " used by multiple clients");
                }
                usedPorts.add(portKey);
            }
        }
    }

    private ManagedFixClient createManagedClient(String clientStreamName) throws FixClientPoolException {
        try {
            ClientDefinition clientDef = config.getClients().get(clientStreamName);
            ConnectionEnvironment connEnv = config.getConnections().get(environmentName);

            return new ManagedFixClient(
                    clientStreamName,
                    config.getCommon(),
                    clientDef,
                    connEnv,
                    portsConfig,
                    createMessageListenerWrapper(clientStreamName),
                    globalSessionEventListener
            );

        } catch (Exception e) {
            throw new FixClientPoolException("Failed to create client: " + clientStreamName, e);
        }
    }

    private MessageListener createMessageListenerWrapper(String clientStreamName) {
        if (globalMessageListener == null) {
            return null;
        }

        return (sessionId, message) -> {
            messagesReceived.incrementAndGet();
            clientMessageCounts.get(clientStreamName).incrementAndGet();
            globalMessageListener.onMessage(sessionId, message);
        };
    }

    private ManagedFixClient getClient(String clientStreamName) throws FixClientPoolException {
        ManagedFixClient client = clientPool.get(clientStreamName);
        if (client == null) {
            throw new FixClientPoolException("Client not found: " + clientStreamName);
        }
        return client;
    }

    private void waitForClients(Set<String> clientNames) throws InterruptedException, FixClientPoolException {
        if (clientNames.isEmpty()) {
            return;
        }

        logger.info("Waiting for {} clients to connect...", clientNames.size());

        CompletableFuture<Void>[] futures = clientNames.stream()
                .map(clientName -> {
                    ManagedFixClient client = clientPool.get(clientName);
                    return CompletableFuture.runAsync(() -> {
                        try {
                            if (!client.waitForConnection(30, TimeUnit.SECONDS)) {
                                throw new RuntimeException("Client " + clientName + " failed to connect within timeout");
                            }
                            logger.info("Client {} connected successfully", clientName);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while waiting for " + clientName, e);
                        }
                    });
                })
                .toArray(CompletableFuture[]::new);

        try {
            // Slight buffer over individual timeouts to allow for some variance
            CompletableFuture.allOf(futures).get(35, TimeUnit.SECONDS);
            logger.info("All {} clients connected successfully", clientNames.size());
        } catch (ExecutionException | TimeoutException e) {
            throw new FixClientPoolException("Not all clients connected within timeout", e);
        }
    }



    private void performHealthCheck() {
        try {
            for (Map.Entry<String, ManagedFixClient> entry : clientPool.entrySet()) {
                String clientName = entry.getKey();
                ManagedFixClient client = entry.getValue();

                if (!client.isFullyConnected()) {
                    logger.warn("Health check: Client {} is not fully connected", clientName);

                    if (globalSessionEventListener != null) {
                        // Note: This assumes the SessionEventListener interface might be extended
                        // with additional methods like onConnectionLost
                        // For now, we'll just log the issue
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error during health check", e);
        }
    }

    private void forceStop() {
        logger.warn("Performing forced shutdown of client pool");

        if (healthCheckTask != null) {
            healthCheckTask.cancel(true);
            healthCheckTask = null;
        }

        for (ManagedFixClient client : clientPool.values()) {
            try {
                client.stop();
            } catch (Exception e) {
                logger.error("Error force-stopping client {}", client.getClientStreamName(), e);
            }
        }

        clientPool.clear();

        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Scheduler did not terminate within 5 seconds after shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for scheduler termination");
        }

        isStarted.set(false);
    }

    private void shutdownScheduler() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Scheduler did not terminate gracefully, forcing shutdown");
                scheduler.shutdownNow();

                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    logger.error("Scheduler did not terminate even after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
}