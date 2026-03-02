package com.marketdatahub.listener;

import com.marketdatahub.model.ParsedMessage;

/**
 * Callback interface delivered to each {@link com.marketdatahub.feed.MarketFeed} at startup.
 * <p>
 * Feed implementations are responsible for parsing raw transport messages and
 * invoking {@link #onMessage(ParsedMessage)} for every normalised update.
 * Implementations of this interface (typically {@link com.marketdatahub.book.MarketBook})
 * must be thread-safe because multiple feeds may call it concurrently.
 */
@FunctionalInterface
public interface MarketDataListener {

    /**
     * Called by a feed whenever a normalised market-data update is available.
     *
     * @param message the parsed, non-null market data update
     */
    void onMessage(ParsedMessage message);
}
