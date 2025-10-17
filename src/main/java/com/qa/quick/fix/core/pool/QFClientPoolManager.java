package com.qa.quick.fix.core.pool;

import com.qa.quick.fix.cfg.ClientDefinition;
import com.qa.quick.fix.cfg.ConnectionEnvironment;
import com.qa.quick.fix.cfg.FixClientConfiguration;
import com.qa.quick.fix.cfg.PortsConfiguration;
import com.qa.quick.fix.core.client.QFClientStatus;
import com.qa.quick.fix.core.client.QFConnector;
import com.qa.quick.fix.core.listeners.QFInboundMessageListener;
import com.qa.quick.fix.core.listeners.QFOutboundMessageListener;
import com.qa.quick.fix.core.listeners.QFSessionEventListener;
import com.qa.quick.fix.exceptions.QFClientPoolException;
import com.qa.quick.fix.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import quickfix.Message;
import quickfix.ConfigError;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.time.Duration;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class QFClientPoolManager {

    private final FixClientConfiguration config;
    private final PortsConfiguration portsConfig;
    private final String environmentName;
    private final Set<String> clientStreamNames;
    private final Map<String, QFConnector> clientPool = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final AtomicBoolean isStarted = new AtomicBoolean(false);

    private QFInboundMessageListener globalQFInboundMessageListener;
    private QFSessionEventListener globalQFSessionEventListener;
    private QFOutboundMessageListener globalQFOutboundMessageListener;

    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final Map<String, AtomicLong> clientMessageCounts = new ConcurrentHashMap<>();

    private final AtomicInteger tradeIndex = new AtomicInteger(0);
    private final AtomicInteger quoteIndex = new AtomicInteger(0);

    public QFClientPoolManager(
            String configPath,
            String portsConfigPath,
            String environmentName,
            Set<String> clientStreamNames)
            throws IOException, QFClientPoolException {
        this.environmentName = environmentName;
        this.clientStreamNames = new HashSet<>(clientStreamNames);
        this.config = loadConfiguration(configPath);
        this.portsConfig = portsConfigPath != null ? loadPortsConfiguration(portsConfigPath) : null;

        validateConfiguration();

        clientStreamNames.forEach(name -> clientMessageCounts.put(name, new AtomicLong(0)));

        log.info("Pool manager initialized for environment {} with clients {}",
                environmentName, clientStreamNames);
    }

    public QFClientPoolManager(
            String configPath,
            String environmentName,
            Set<String> clientStreamNames)
            throws IOException, QFClientPoolException {
        this(configPath, null, environmentName, clientStreamNames);
    }

    public QFClientPoolManager(
            FixClientConfiguration fixClientConfiguration,
            PortsConfiguration portsConfiguration,
            String environmentName,
            Set<String> clientStreamNames)
            throws QFClientPoolException {
        this.config = fixClientConfiguration;
        this.portsConfig = portsConfiguration;
        this.environmentName = environmentName;
        this.clientStreamNames = new HashSet<>(clientStreamNames);

        validateConfiguration();

        clientStreamNames.forEach(name -> clientMessageCounts.put(name, new AtomicLong(0)));

        log.info("Pool manager initialized for environment {} with clients {}",
                environmentName, clientStreamNames);
    }

    public QFClientPoolManager(
            FixClientConfiguration fixClientConfiguration,
            String environmentName,
            Set<String> clientStreamNames)
            throws QFClientPoolException {
        this(fixClientConfiguration, null, environmentName, clientStreamNames);
    }

    public void startAll() throws QFClientPoolException {
        var result = startAll(false);
        if (!result.isAllSuccessful()) {
            throw new QFClientPoolException(
                    "Failed to start all clients: " + result.getFailedClients().keySet());
        }
    }

    public StartupResult startAll(boolean allowPartialSuccess) throws QFClientPoolException {
        if (isStarted.get()) {
            throw new QFClientPoolException("Pool already started");
        }

        Set<String> successful = new HashSet<>();
        Map<String, Exception> failed = new HashMap<>();

        try {
            for (String clientStreamName : clientStreamNames) {
                try {
                    QFConnector client = createQFClient(clientStreamName);
                    client.start();
                    clientPool.put(clientStreamName, client);
                    successful.add(clientStreamName);
                    log.info("Started client: {}", clientStreamName);
                } catch (ConfigError e) {
                    log.error("Configuration error starting client {}: {}", clientStreamName, e.getMessage(), e);
                    failed.put(clientStreamName, e);
                } catch (QFClientPoolException e) {
                    log.error("Pool error starting client {}: {}", clientStreamName, e.getMessage(), e);
                    failed.put(clientStreamName, e);
                } catch (RuntimeException e) {
                    log.error("Unexpected error starting client {}: {}", clientStreamName, e.getMessage(), e);
                    failed.put(clientStreamName, e);
                }
            }

            if (successful.isEmpty()) {
                throw new QFClientPoolException("No clients started successfully");
            }

            waitForClients(successful);

            boolean allSuccessful = failed.isEmpty();
            if (!allSuccessful && !allowPartialSuccess) {
                stopAll();
                throw new QFClientPoolException(
                        "Failed to start all clients: " + failed.keySet());
            }

            isStarted.set(true);
            log.info("Client pool started - Successful: {}, Failed: {}",
                    successful.size(), failed.size());

            return new StartupResult(successful, failed, allSuccessful);

        } catch (Exception e) {
            forceStop();
            if (e instanceof QFClientPoolException) {
                throw (QFClientPoolException) e;
            }
            throw new QFClientPoolException("Failed to start client pool", e);
        }
    }

    public void stopAll() {
        stopAll(30, TimeUnit.SECONDS);
    }

    public boolean stopAll(long timeout, TimeUnit unit) {
        log.info("Stopping FIX client pool with timeout: {} {}", timeout, unit);

        long startTime = System.nanoTime();
        long timeoutNanos = unit.toNanos(timeout);

        List<CompletableFuture<Void>> stopFutures = new ArrayList<>();

        for (QFConnector client : clientPool.values()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    client.stop();
                    log.debug("Stopped client: {}", client.getClientStreamName());
                } catch (RuntimeException e) {
                    log.error("Error stopping client {}", client.getClientStreamName(), e);
                }
            }, scheduler);
            stopFutures.add(future);
        }

        try {
            long remainingNanos = timeoutNanos - (System.nanoTime() - startTime);
            CompletableFuture.allOf(stopFutures.toArray(new CompletableFuture[0]))
                    .get(Math.max(1, remainingNanos), TimeUnit.NANOSECONDS);

            clientPool.clear();
            shutdownScheduler();

            isStarted.set(false);
            log.info("FIX client pool stopped successfully");
            return true;

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.error("Timeout during graceful shutdown, forcing stop", e);
            forceStop();
            return false;
        }
    }

    public void sendTradeMessage(String clientStreamName, Message message)
            throws QFClientPoolException {
        checkStarted();
        QFConnector client = getClient(clientStreamName);
        client.sendTradeMessage(message);
    }

    public void sendQuoteMessage(String clientStreamName, Message message)
            throws QFClientPoolException {
        checkStarted();
        QFConnector client = getClient(clientStreamName);
        client.sendQuoteMessage(message);
    }

    public void sendTradeMessage(Message message) throws QFClientPoolException {
        checkStarted();
        var selectedClient = getTradeClient();
        sendTradeMessage(selectedClient, message);
    }

    public void sendQuoteMessage(Message message) throws QFClientPoolException {
        checkStarted();
        var selectedClient = getQuoteClient();
        sendQuoteMessage(selectedClient, message);
    }

    public QFClientStatus getClientStatus(String clientStreamName) {
        QFConnector client = clientPool.get(clientStreamName);
        return client != null ? client.getStatus() : null;
    }

    public Map<String, QFClientStatus> getAllClientStatuses() {
        Map<String, QFClientStatus> statuses = new HashMap<>();
        for (Map.Entry<String, QFConnector> entry : clientPool.entrySet()) {
            statuses.put(entry.getKey(), entry.getValue().getStatus());
        }
        return statuses;
    }

    public boolean isClientConnected(String clientStreamName) {
        QFConnector client = clientPool.get(clientStreamName);
        return client != null && client.isConnected();
    }

    public boolean hasQuoteSession(String clientStreamName) {
        QFConnector client = clientPool.get(clientStreamName);
        return client != null && client.hasQuoteSession();
    }

    public boolean isTradeSessionConnected(String clientStreamName) {
        QFConnector client = clientPool.get(clientStreamName);
        return client != null && client.isTradeSessionConnected();
    }

    public boolean isQuoteSessionConnected(String clientStreamName) {
        QFConnector client = clientPool.get(clientStreamName);
        return client != null && client.isQuoteSessionConnected();
    }

    public Set<String> getAvailableClients() {
        return new HashSet<>(clientPool.keySet());
    }

    public PoolStatistics getStatistics() {
        var connectedCount = (int) clientPool.values()
                .stream()
                .filter(QFConnector::isConnected)
                .count();

        return new PoolStatistics(
                clientPool.size(),
                connectedCount,
                messagesSent.get(),
                messagesReceived.get(),
                clientMessageCounts
        );
    }

    public boolean isStarted() {
        return isStarted.get();
    }

    public void setGlobalMessageListener(QFInboundMessageListener listener) {
        this.globalQFInboundMessageListener = listener;
    }

    public void setGlobalOutboundMessageListener(QFOutboundMessageListener listener) {
        this.globalQFOutboundMessageListener = listener;
    }

    public void setGlobalSessionEventListener(QFSessionEventListener listener) {
        this.globalQFSessionEventListener = listener;
    }

    public synchronized boolean stopClient(String clientStreamName, long timeout, TimeUnit unit)
            throws QFClientPoolException {
        checkStarted();

        var client = clientPool.remove(clientStreamName);
        if (client == null) {
            log.warn("Cannot stop client – not found: {}", clientStreamName);
            return false;
        }

        log.info("Stopping client: {}", clientStreamName);

        CompletableFuture<Void> stopFuture = CompletableFuture.runAsync(() -> {
            try {
                client.stop();
                log.info("Client {} stopped successfully", clientStreamName);
            } catch (Exception e) {
                log.error("Error stopping client {}", clientStreamName, e);
                throw new RuntimeException(e);
            }
        });

        try {
            stopFuture.get(timeout, unit);
            var count = clientMessageCounts.get(clientStreamName);
            if (count != null) {
                count.set(0);
            } else {
                log.warn("No message count found for client {} during stop", clientStreamName);
            }

            log.info("Client {} stopped gracefully", clientStreamName);
            return true;

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.error("Failed to stop client {} within timeout", clientStreamName, e);
            return false;
        }
    }

    public boolean stopClient(String clientStreamName) throws QFClientPoolException {
        return stopClient(clientStreamName, 30, TimeUnit.SECONDS);
    }

    public boolean restartClient(
            String clientStreamName, long startTimeout, TimeUnit unit)
            throws QFClientPoolException {
        checkStarted();

        if (!clientStreamNames.contains(clientStreamName)) {
            throw new QFClientPoolException("Client not in configured set: " + clientStreamName);
        }

        log.info("Restarting client: {}", clientStreamName);

        try {
            QFConnector client = clientPool.get(clientStreamName);

            if (client == null) {
                log.info("Client {} not running, creating a new instance", clientStreamName);
                QFConnector newClient = createQFClient(clientStreamName);
                // Add to pool before starting to make it discoverable consistently
                clientPool.put(clientStreamName, newClient);
                try {
                    newClient.start();
                    log.info("Started client {} and awaiting connection...", clientStreamName);
                    boolean connected = newClient.waitForConnection(startTimeout, unit);
                    if (connected) {
                        log.info("Client {} started and connected", clientStreamName);
                        return true;
                    } else {
                        log.error("Client {} failed to connect within {} {}", clientStreamName, startTimeout, unit);
                        clientPool.remove(clientStreamName);
                        return false;
                    }
                } catch (Exception e) {
                    clientPool.remove(clientStreamName);
                    throw new QFClientPoolException("Failed to start new client: " + clientStreamName, e);
                }
            }

            log.info("Invoking in-place restart on client {}", clientStreamName);
            boolean connected;
            try {
                connected = client.restartAndAwait(startTimeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QFClientPoolException("Interrupted during restart of client: " + clientStreamName, e);
            }

            if (connected) {
                log.info("Client {} restarted and connected successfully", clientStreamName);
                return true;
            } else {
                log.error("Client {} failed to connect after restart within {} {}", clientStreamName, startTimeout, unit);
                return false;
            }

        } catch (QFClientPoolException e) {
            log.error("Pool error during restart of client {}: {}", clientStreamName, e.getMessage(), e);
            throw e;
        } catch (ConfigError e) {
            log.error("Configuration error during restart of client {}: {}", clientStreamName, e.getMessage(), e);
            throw new QFClientPoolException("Failed to restart client: " + clientStreamName, e);
        } catch (RuntimeException e) {
            log.error("Unexpected error during restart of client {}: {}", clientStreamName, e.getMessage(), e);
            throw new QFClientPoolException("Failed to restart client: " + clientStreamName, e);
        }
    }

    public Set<String> getRunningClients() {
        return new HashSet<>(clientPool.keySet());
    }

    public Set<String> getConfiguredClients() {
        return new HashSet<>(clientStreamNames);
    }

    public Set<String> getStoppedClients() {
        Set<String> stopped = new HashSet<>(clientStreamNames);
        stopped.removeAll(clientPool.keySet());
        return stopped;
    }

    private void checkStarted() throws QFClientPoolException {
        if (!isStarted.get()) {
            throw new QFClientPoolException("Pool is not started");
        }
    }

    private FixClientConfiguration loadConfiguration(String configPath) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(configPath)) {
            if (inputStream != null) {
                log.info("Loading main configuration from classpath: {}", configPath);
                return JsonUtil.read(inputStream, FixClientConfiguration.class);
            }
        }
        log.info("Loading main configuration from file system: {}", configPath);
        return JsonUtil.read(new File(configPath), FixClientConfiguration.class);
    }

    private PortsConfiguration loadPortsConfiguration(String portsConfigPath) throws IOException {
        if (portsConfigPath == null) {
            return null;
        }

        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(portsConfigPath)) {
            if (inputStream != null) {
                log.info("Loading ports configuration from classpath: {}", portsConfigPath);
                return JsonUtil.read(inputStream, PortsConfiguration.class);
            }
        }
        log.info("Loading ports configuration from file system: {}", portsConfigPath);
        return JsonUtil.read(new File(portsConfigPath), PortsConfiguration.class);
    }

    private void validateConfiguration() throws QFClientPoolException {
        if (!config.getConnections().containsKey(environmentName)) {
            throw new QFClientPoolException("Environment not found: " + environmentName);
        }

        var env = config.getConnections().get(environmentName);

        for (String clientStreamName : clientStreamNames) {
            if (!config.getClients().containsKey(clientStreamName)) {
                throw new QFClientPoolException("Client not found in configuration: " + clientStreamName);
            }

            var clientDef = config.getClients().get(clientStreamName);

            if (clientDef.getTradeSession() == null && clientDef.getQuoteSession() == null) {
                throw new QFClientPoolException(
                        "Client " + clientStreamName + " has no sessions (trade or quote) configured");
            }

            if (clientDef.getTradeSession() != null && env.getTrade() == null) {
                throw new QFClientPoolException(
                        "Client " + clientStreamName
                                + " needs a trade session but no trade connection configured for environment "
                                + environmentName);
            }

            if (clientDef.getQuoteSession() != null && env.getQuote() == null) {
                throw new QFClientPoolException(
                        "Client " + clientStreamName
                                + " needs a quote session but no quote connection configured for environment "
                                + environmentName);
            }
        }

        log.info(
                "Configuration validated successfully for environment '{}' and clients: {}",
                environmentName,
                clientStreamNames
        );
    }

    private QFConnector createQFClient(String clientStreamName) throws QFClientPoolException {
        try {
            ClientDefinition clientDef = config.getClients().get(clientStreamName);
            ConnectionEnvironment connEnv = config.getConnections().get(environmentName);

            return new QFConnector(
                    clientStreamName,
                    config.getCommon(),
                    clientDef,
                    connEnv,
                    portsConfig,
                    createInboundMessageListenerWrapper(clientStreamName),
                    globalQFSessionEventListener,
                    createOutboundMessageListenerWrapper(clientStreamName)
            );
        } catch (RuntimeException e) {
            throw new QFClientPoolException(
                    "Failed to create client connector for: " + clientStreamName, e);
        }
    }

    private QFInboundMessageListener createInboundMessageListenerWrapper(String clientStreamName) {
        if (globalQFInboundMessageListener == null) {
            return null;
        }

        return (sessionId, message) -> {
            messagesReceived.incrementAndGet();
            clientMessageCounts.get(clientStreamName).incrementAndGet();
            globalQFInboundMessageListener.onMessage(sessionId, message);
        };
    }

    private QFOutboundMessageListener createOutboundMessageListenerWrapper(String clientStreamName) {
        if (globalQFOutboundMessageListener == null) {
            return null;
        }

        return (sessionId, message) -> {
            messagesSent.incrementAndGet();
            clientMessageCounts.get(clientStreamName).incrementAndGet();
            globalQFOutboundMessageListener.onOutgoingMessage(sessionId, message);
        };
    }

    private QFConnector getClient(String clientStreamName) throws QFClientPoolException {
        QFConnector client = clientPool.get(clientStreamName);
        if (client == null) {
            throw new QFClientPoolException("Client not found in pool: " + clientStreamName);
        }
        return client;
    }

    private String getTradeClient() throws QFClientPoolException {
        Set<String> tradeClients = getConnectedTradeClients();
        if (tradeClients.isEmpty()) {
            throw new QFClientPoolException("No connected trade clients available");
        }

        List<String> tradeClientList = new ArrayList<>(tradeClients);
        var index = tradeIndex.getAndUpdate(i -> (i + 1) % tradeClientList.size());
        return tradeClientList.get(index);
    }

    private String getQuoteClient() throws QFClientPoolException {
        Set<String> quoteClients = getConnectedQuoteClients();
        if (quoteClients.isEmpty()) {
            throw new QFClientPoolException("No connected quote clients available");
        }

        List<String> quoteClientList = new ArrayList<>(quoteClients);
        var index = quoteIndex.getAndUpdate(i -> (i + 1) % quoteClientList.size());
        return quoteClientList.get(index);
    }

    public Set<String> getConnectedTradeClients() {
        Set<String> connectedClients = new HashSet<>();
        for (Map.Entry<String, QFConnector> entry : clientPool.entrySet()) {
            if (entry.getValue().isTradeSessionConnected()) {
                connectedClients.add(entry.getKey());
            }
        }
        return connectedClients;
    }

    public Set<String> getConnectedQuoteClients() {
        Set<String> connectedClients = new HashSet<>();
        for (Map.Entry<String, QFConnector> entry : clientPool.entrySet()) {
            if (entry.getValue().isQuoteSessionConnected()) {
                connectedClients.add(entry.getKey());
            }
        }
        return connectedClients;
    }

    private void waitForClients(Set<String> clientNames)
            throws InterruptedException, QFClientPoolException {
        if (clientNames.isEmpty()) {
            return;
        }

        log.info("Waiting for {} clients to connect...", clientNames.size());
        CompletableFuture<Void>[] futures = clientNames.stream()
                .map(clientName -> {
                    QFConnector client = clientPool.get(clientName);
                    if (client == null) {
                        return CompletableFuture.failedFuture(
                                new QFClientPoolException("Client " + clientName + " not found during connection wait."));
                    }
                    return CompletableFuture.runAsync(() -> {
                        if (!client.awaitConnected(Duration.ofSeconds(30))) {
                            throw new RuntimeException("Client " + clientName + " failed to connect within timeout (30s)");
                        }
                        log.info("Client {} connected successfully", clientName);
                    }, scheduler);
                })
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(futures).get(35, TimeUnit.SECONDS);
            log.info("All {} clients connected successfully within overall timeout", clientNames.size());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof QFClientPoolException) {
                throw (QFClientPoolException) cause;
            } else if (cause instanceof RuntimeException) {
                throw new QFClientPoolException("Error during client connection wait: " + cause.getMessage(), cause);
            }
            throw new QFClientPoolException("Failed to connect all clients", e);
        } catch (TimeoutException e) {
            throw new QFClientPoolException("Not all clients connected within overall timeout (35s)", e);
        }
    }

    private void forceStop() {
        log.warn("Performing forced shutdown of client pool");

        for (QFConnector client : clientPool.values()) {
            try {
                client.stop();
            } catch (Exception e) {
                log.error("Error force-stopping client {}: {}", client.getClientStreamName(), e.getMessage());
            }
        }

        clientPool.clear();

        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate within 5 seconds after shutdownNow. Some tasks might be stuck.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for scheduler termination during force stop");
        }

        isStarted.set(false);
        log.info("Client pool force-stopped.");
    }

    /**
     * Shuts down the internal {@link ScheduledExecutorService} gracefully.
     * It attempts a graceful shutdown first, then a forced shutdown
     * if tasks do not terminate within a timeout.
     */
    private void shutdownScheduler() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate gracefully within 5 seconds, forcing shutdown.");
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.error("Scheduler did not terminate even after forced shutdown within 2 seconds. Tasks may be hung.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for scheduler termination. Forcing shutdown.");
            scheduler.shutdownNow();
        }
    }









}
