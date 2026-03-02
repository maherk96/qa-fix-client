package com.marketdatahub.feed.solace;

import com.marketdatahub.config.SolaceConfig;
import com.marketdatahub.feed.MarketFeed;
import com.marketdatahub.listener.MarketDataListener;
import com.marketdatahub.model.ParsedMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract base class for a Solace PubSub+ market-data feed.
 * <p>
 * This class manages the feed lifecycle ({@link #start}/{@link #stop},
 * running-state tracking) and delegates actual message consumption to
 * {@link #onRawMessage(Object)}, which subclasses implement using their
 * existing Solace consumer logic.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MySolaceFeed extends SolaceFeed {
 *
 *     public MySolaceFeed(String marketName, SolaceConfig config) {
 *         super(marketName, config);
 *     }
 *
 *     @Override
 *     protected void onRawMessage(Object rawMessage) {
 *         // parse rawMessage (e.g. BytesXMLMessage) into a ParsedMessage
 *         // then call: getListener().onMessage(parsedMessage);
 *     }
 *
 *     @Override
 *     protected void connectAndSubscribe() {
 *         // establish your Solace session and subscriber here
 *     }
 *
 *     @Override
 *     protected void disconnectAndUnsubscribe() {
 *         // clean up your Solace session here
 *     }
 * }
 * }</pre>
 */
@Slf4j
public abstract class SolaceFeed implements MarketFeed {

    private final String marketName;
    private final SolaceConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** The listener registered at {@link #start(MarketDataListener)} time. */
    private volatile MarketDataListener listener;

    /**
     * Constructs a {@code SolaceFeed} for the given market.
     *
     * @param marketName unique logical name for this market (e.g. {@code "EBS"})
     * @param config     Solace-specific connection parameters
     */
    protected SolaceFeed(String marketName, SolaceConfig config) {
        if (marketName == null || marketName.isBlank()) {
            throw new IllegalArgumentException("marketName must not be null or blank");
        }
        if (config == null) {
            throw new IllegalArgumentException("SolaceConfig must not be null");
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
            throw new IllegalStateException("SolaceFeed '" + marketName + "' is already running");
        }
        this.listener = listener;
        log.info("Starting SolaceFeed for market='{}' host='{}' topic='{}'",
                marketName, config.getHost(), config.getTopic());
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
            log.debug("SolaceFeed '{}' is not running – stop() is a no-op", marketName);
            return;
        }
        log.info("Stopping SolaceFeed for market='{}'", marketName);
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
     * Solace message arrives on the subscribed topic.
     * <p>
     * Implementations should parse {@code rawMessage} into a {@link ParsedMessage}
     * and deliver it via {@link #getListener()}{@code .onMessage(...)}.
     *
     * @param rawMessage the raw transport message object (e.g. {@code BytesXMLMessage})
     */
    protected abstract void onRawMessage(Object rawMessage);

    /**
     * Called by {@link #start} to establish the Solace session and create the
     * topic subscription. The implementation must arrange for incoming messages
     * to be delivered to {@link #onRawMessage(Object)}.
     */
    protected abstract void connectAndSubscribe();

    /**
     * Called by {@link #stop} to tear down the Solace session and release all
     * associated resources.
     */
    protected abstract void disconnectAndUnsubscribe();

    // -----------------------------------------------------------------------
    // Protected accessors for subclasses
    // -----------------------------------------------------------------------

    /**
     * Returns the Solace configuration supplied at construction time.
     *
     * @return the {@link SolaceConfig}
     */
    protected SolaceConfig getConfig() {
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
