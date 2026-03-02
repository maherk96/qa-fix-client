package com.marketdatahub.feed;

import com.marketdatahub.listener.MarketDataListener;

/**
 * Abstraction over a single market-data feed connection.
 * <p>
 * Implementations are responsible for establishing a transport connection,
 * subscribing to the configured subject/topic, and delivering normalised
 * {@link com.marketdatahub.model.ParsedMessage} instances to the supplied
 * {@link MarketDataListener}.
 *
 * <p>Lifecycle contract:
 * <ol>
 *   <li>{@link #start(MarketDataListener)} — connect and begin delivering messages</li>
 *   <li>{@link #stop()} — disconnect and release all transport resources</li>
 * </ol>
 *
 * Implementations must be thread-safe; {@code start} and {@code stop} may be
 * called from different threads.
 */
public interface MarketFeed {

    /**
     * Starts the feed and registers the listener that will receive normalised updates.
     * <p>
     * This method should return promptly; actual message delivery happens on
     * implementation-managed threads.
     *
     * @param listener the non-null listener to deliver messages to
     * @throws IllegalStateException if the feed is already running
     */
    void start(MarketDataListener listener);

    /**
     * Stops the feed, disconnecting from the transport and releasing resources.
     * Calling {@code stop} on an already-stopped feed is a no-op.
     */
    void stop();

    /**
     * Returns the logical market name this feed belongs to (e.g. {@code "EBS"}).
     *
     * @return the non-null market name
     */
    String getMarketName();

    /**
     * Returns {@code true} if the feed has been started and has not yet been stopped.
     *
     * @return whether the feed is currently active
     */
    boolean isRunning();
}
