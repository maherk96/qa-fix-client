package com.marketdatahub.feed.tibrv;

import com.marketdatahub.config.TibrvConfig;
import com.marketdatahub.feed.MarketFeed;
import com.marketdatahub.listener.MarketDataListener;
import com.marketdatahub.model.ParsedMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract base class for a TIBCO Rendezvous (TIBRV) market-data feed.
 * <p>
 * This class manages the feed lifecycle ({@link #start}/{@link #stop},
 * running-state tracking) and delegates actual message consumption to
 * {@link #onRawMessage(Object)}, which subclasses implement using their
 * existing TIBRV listener logic.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyTibrvFeed extends TibrvFeed {
 *
 *     public MyTibrvFeed(String marketName, TibrvConfig config) {
 *         super(marketName, config);
 *     }
 *
 *     @Override
 *     protected void onRawMessage(Object rawMessage) {
 *         // parse rawMessage (e.g. TibrvMsg) into a ParsedMessage
 *         // then call: getListener().onMessage(parsedMessage);
 *     }
 *
 *     @Override
 *     protected void connectAndSubscribe() {
 *         // open Tibrv, create TibrvRvdTransport, add listener here
 *     }
 *
 *     @Override
 *     protected void disconnectAndUnsubscribe() {
 *         // close transport and Tibrv here
 *     }
 * }
 * }</pre>
 */
@Slf4j
public abstract class TibrvFeed implements MarketFeed {

    private final String marketName;
    private final TibrvConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** The listener registered at {@link #start(MarketDataListener)} time. */
    private volatile MarketDataListener listener;

    /**
     * Constructs a {@code TibrvFeed} for the given market.
     *
     * @param marketName unique logical name for this market (e.g. {@code "REUTERS"})
     * @param config     TIBRV-specific connection parameters
     */
    protected TibrvFeed(String marketName, TibrvConfig config) {
        if (marketName == null || marketName.isBlank()) {
            throw new IllegalArgumentException("marketName must not be null or blank");
        }
        if (config == null) {
            throw new IllegalArgumentException("TibrvConfig must not be null");
        }
        this.marketName = marketName;
        this.config = config;
    }

    // -----------------------------------------------------------------------
    // MarketFeed
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     * <p>
     * Stores the listener and delegates connection establishment to
     * {@link #connectAndSubscribe()}.
     *
     * @throws IllegalStateException if the feed is already running
     */
    @Override
    public final void start(MarketDataListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("TibrvFeed '" + marketName + "' is already running");
        }
        this.listener = listener;
        log.info("Starting TibrvFeed for market='{}' daemon='{}' subject='{}'",
                marketName, config.getDaemon(), config.getSubject());
        connectAndSubscribe();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Delegates resource cleanup to {@link #disconnectAndUnsubscribe()}.
     */
    @Override
    public final void stop() {
        if (!running.compareAndSet(true, false)) {
            log.debug("TibrvFeed '{}' is not running – stop() is a no-op", marketName);
            return;
        }
        log.info("Stopping TibrvFeed for market='{}'", marketName);
        disconnectAndUnsubscribe();
    }

    /** {@inheritDoc} */
    @Override
    public final String getMarketName() {
        return marketName;
    }

    /** {@inheritDoc} */
    @Override
    public final boolean isRunning() {
        return running.get();
    }

    // -----------------------------------------------------------------------
    // Template methods for subclasses
    // -----------------------------------------------------------------------

    /**
     * Called by the framework on a transport-delivery thread whenever a raw
     * TIBRV message arrives on the subscribed subject.
     * <p>
     * Implementations should parse {@code rawMessage} into a {@link ParsedMessage}
     * and deliver it via {@link #getListener()}{@code .onMessage(...)}.
     *
     * @param rawMessage the raw transport message object (e.g. {@code TibrvMsg})
     */
    protected abstract void onRawMessage(Object rawMessage);

    /**
     * Called by {@link #start} to open the Tibrv environment, create the
     * transport, and register a subject listener that routes to
     * {@link #onRawMessage(Object)}.
     */
    protected abstract void connectAndSubscribe();

    /**
     * Called by {@link #stop} to close the TIBRV transport and release all
     * associated resources.
     */
    protected abstract void disconnectAndUnsubscribe();

    // -----------------------------------------------------------------------
    // Protected accessors for subclasses
    // -----------------------------------------------------------------------

    /**
     * Returns the TIBRV configuration supplied at construction time.
     *
     * @return the {@link TibrvConfig}
     */
    protected TibrvConfig getConfig() {
        return config;
    }

    /**
     * Returns the {@link MarketDataListener} that was registered when
     * {@link #start(MarketDataListener)} was called.
     *
     * @return the active listener, or {@code null} if the feed has not been started
     */
    protected MarketDataListener getListener() {
        return listener;
    }
}
