package com.marketdatahub.feed;

import com.marketdatahub.config.MarketConfig;
import com.marketdatahub.config.MarketDataHubConfig;
import com.marketdatahub.listener.MarketDataListener;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of all configured {@link MarketFeed} instances.
 * <p>
 * Each feed runs on its own dedicated thread drawn from an internal
 * {@link ExecutorService}. The manager is fully thread-safe: start/stop calls
 * for different markets can proceed concurrently without data corruption.
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * FeedManager mgr = new FeedManager(config, factory, listener);
 * mgr.startFeeds(List.of("EBS", "REUTERS"));
 * // ... later ...
 * mgr.stopAll();
 * }</pre>
 */
@Slf4j
public class FeedManager {

    private final MarketDataHubConfig config;
    private final MarketFeedFactory factory;
    private final MarketDataListener listener;

    /** Live feed instances keyed by market name. */
    private final ConcurrentHashMap<String, MarketFeed> feeds = new ConcurrentHashMap<>();

    /** Tracks the Future returned when each feed's start task was submitted. */
    private final ConcurrentHashMap<String, Future<?>> feedFutures = new ConcurrentHashMap<>();

    /**
     * Thread pool — one thread per market so feeds are isolated from one another.
     * The pool is sized lazily via {@link Executors#newCachedThreadPool()} to avoid
     * pre-allocating threads for markets that are never started.
     */
    private final ExecutorService executor;

    /**
     * Creates a {@code FeedManager}.
     *
     * @param config   the hub configuration that contains all market configs
     * @param factory  factory used to instantiate {@link MarketFeed} objects
     * @param listener the listener to pass to each feed on start
     */
    public FeedManager(MarketDataHubConfig config, MarketFeedFactory factory, MarketDataListener listener) {
        if (config == null)   throw new IllegalArgumentException("config must not be null");
        if (factory == null)  throw new IllegalArgumentException("factory must not be null");
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        this.config   = config;
        this.factory  = factory;
        this.listener = listener;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("market-feed-" + System.nanoTime());
            return t;
        });
    }

    /**
     * Starts the feeds identified by the supplied market names.
     * <p>
     * A feed is created via the factory if it does not yet exist, then submitted to the
     * executor. Markets that are already running are silently skipped.
     *
     * @param marketNames the list of market names to start; must not be {@code null}
     * @throws IllegalArgumentException if any name in the list is not present in the config
     */
    public void startFeeds(List<String> marketNames) {
        if (marketNames == null) throw new IllegalArgumentException("marketNames must not be null");
        for (String name : marketNames) {
            startSingleFeed(name);
        }
    }

    /**
     * Stops the feeds identified by the supplied market names.
     * <p>
     * Markets that are not running are silently skipped.
     *
     * @param marketNames the list of market names to stop; must not be {@code null}
     */
    public void stopFeeds(List<String> marketNames) {
        if (marketNames == null) throw new IllegalArgumentException("marketNames must not be null");
        for (String name : marketNames) {
            stopSingleFeed(name);
        }
    }

    /**
     * Stops all running feeds and shuts down the internal executor.
     * <p>
     * Blocks for up to 10 seconds waiting for the executor to terminate gracefully
     * before forcing a shutdown.
     */
    public void stopAll() {
        log.info("Stopping all feeds");
        feeds.keySet().forEach(this::stopSingleFeed);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate within 10 s – forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /**
     * Returns a snapshot of the current {@link FeedStatus} for every configured market.
     * <p>
     * A market that has been configured but never started is reported as {@link FeedStatus#STOPPED}.
     *
     * @return an unmodifiable map of market name → {@link FeedStatus}
     */
    public Map<String, FeedStatus> getStatus() {
        Map<String, FeedStatus> result = new HashMap<>();
        for (String marketName : config.getMarkets().keySet()) {
            MarketFeed feed = feeds.get(marketName);
            if (feed == null) {
                result.put(marketName, FeedStatus.STOPPED);
            } else if (feed.isRunning()) {
                result.put(marketName, FeedStatus.RUNNING);
            } else {
                // Future completed but feed is not running → it crashed or exited prematurely
                Future<?> future = feedFutures.get(marketName);
                result.put(marketName,
                        (future != null && future.isDone()) ? FeedStatus.ERROR : FeedStatus.STOPPED);
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void startSingleFeed(String marketName) {
        MarketConfig marketConfig = config.getMarkets().get(marketName);
        if (marketConfig == null) {
            throw new IllegalArgumentException("Unknown market: '" + marketName + "'");
        }

        MarketFeed feed = feeds.computeIfAbsent(marketName,
                name -> factory.createFeed(name, marketConfig));

        if (feed.isRunning()) {
            log.debug("Feed '{}' is already running – skipping start", marketName);
            return;
        }

        log.info("Submitting feed '{}' to executor", marketName);
        Future<?> future = executor.submit(() -> {
            try {
                feed.start(listener);
            } catch (Exception ex) {
                log.error("Feed '{}' failed to start", marketName, ex);
            }
        });
        feedFutures.put(marketName, future);
    }

    private void stopSingleFeed(String marketName) {
        MarketFeed feed = feeds.get(marketName);
        if (feed == null) {
            log.debug("No feed found for market '{}' – skipping stop", marketName);
            return;
        }
        log.info("Stopping feed '{}'", marketName);
        try {
            feed.stop();
        } catch (Exception ex) {
            log.error("Error stopping feed '{}'", marketName, ex);
        }
        Future<?> future = feedFutures.remove(marketName);
        if (future != null) {
            future.cancel(true);
        }
    }
}
