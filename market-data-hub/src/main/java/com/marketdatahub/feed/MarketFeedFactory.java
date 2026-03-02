package com.marketdatahub.feed;

import com.marketdatahub.config.MarketConfig;
import com.marketdatahub.config.SolaceConfig;
import com.marketdatahub.config.TibrvConfig;
import com.marketdatahub.feed.solace.SolaceFeed;
import com.marketdatahub.feed.tibrv.TibrvFeed;
import com.marketdatahub.listener.MarketDataListener;
import com.marketdatahub.model.ParsedMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * Factory responsible for instantiating the correct {@link MarketFeed} implementation
 * for a given {@link MarketConfig}.
 * <p>
 * The default factory creates stub implementations of {@link SolaceFeed} and
 * {@link TibrvFeed} whose {@code connectAndSubscribe}, {@code disconnectAndUnsubscribe},
 * and {@code onRawMessage} methods are no-ops. Replace this factory (or subclass it) to
 * wire in your own concrete feed implementations.
 *
 * <h2>Custom factory example</h2>
 * <pre>{@code
 * public class MyFeedFactory extends MarketFeedFactory {
 *     @Override
 *     public MarketFeed createFeed(String marketName, MarketConfig config) {
 *         return switch (config.getTransportType()) {
 *             case SOLACE -> new MySolaceFeed(marketName, (SolaceConfig) config);
 *             case TIBRV -> new MyTibrvFeed(marketName, (TibrvConfig) config);
 *         };
 *     }
 * }
 * }</pre>
 */
@Slf4j
public class MarketFeedFactory {

    /**
     * Creates a {@link MarketFeed} appropriate for the given market and configuration.
     *
     * @param marketName the logical market name (e.g. {@code "EBS"})
     * @param config     the transport-specific configuration
     * @return a new, un-started {@link MarketFeed} instance
     * @throws IllegalArgumentException if the transport type is unsupported or the
     *                                  config type does not match the declared transport type
     */
    public MarketFeed createFeed(String marketName, MarketConfig config) {
        return switch (config.getTransportType()) {
            case SOLACE -> createSolaceFeed(marketName, (SolaceConfig) config);
            case TIBRV  -> createTibrvFeed(marketName, (TibrvConfig) config);
        };
    }

    // -----------------------------------------------------------------------
    // Default stub implementations — override to plug in real logic
    // -----------------------------------------------------------------------

    /**
     * Creates a no-op stub {@link SolaceFeed}. Override to return a real implementation.
     *
     * @param marketName the logical market name
     * @param config     Solace-specific configuration
     * @return a stub feed
     */
    protected SolaceFeed createSolaceFeed(String marketName, SolaceConfig config) {
        log.debug("Creating stub SolaceFeed for market='{}'", marketName);
        return new SolaceFeed(marketName, config) {
            @Override
            protected void onRawMessage(Object rawMessage) {
                log.trace("Stub SolaceFeed.onRawMessage called for market='{}'", getMarketName());
            }

            @Override
            protected void connectAndSubscribe() {
                log.info("Stub SolaceFeed.connectAndSubscribe — no real connection made for market='{}'",
                        getMarketName());
            }

            @Override
            protected void disconnectAndUnsubscribe() {
                log.info("Stub SolaceFeed.disconnectAndUnsubscribe for market='{}'", getMarketName());
            }
        };
    }

    /**
     * Creates a no-op stub {@link TibrvFeed}. Override to return a real implementation.
     *
     * @param marketName the logical market name
     * @param config     TIBRV-specific configuration
     * @return a stub feed
     */
    protected TibrvFeed createTibrvFeed(String marketName, TibrvConfig config) {
        log.debug("Creating stub TibrvFeed for market='{}'", marketName);
        return new TibrvFeed(marketName, config) {
            @Override
            protected void onRawMessage(Object rawMessage) {
                log.trace("Stub TibrvFeed.onRawMessage called for market='{}'", getMarketName());
            }

            @Override
            protected void connectAndSubscribe() {
                log.info("Stub TibrvFeed.connectAndSubscribe — no real connection made for market='{}'",
                        getMarketName());
            }

            @Override
            protected void disconnectAndUnsubscribe() {
                log.info("Stub TibrvFeed.disconnectAndUnsubscribe for market='{}'", getMarketName());
            }
        };
    }
}
