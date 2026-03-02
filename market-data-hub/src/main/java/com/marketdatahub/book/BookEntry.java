package com.marketdatahub.book;

import com.marketdatahub.model.PriceLevel;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

/**
 * An immutable snapshot of the full order-book ladder for a single instrument symbol.
 * <p>
 * Instances are stored inside {@link MarketBook}'s {@code ConcurrentHashMap} and
 * replaced atomically on each {@link com.marketdatahub.model.ParsedMessage} update;
 * they are therefore safe to hand to external callers without defensive copying.
 * <p>
 * Both {@code bidLevels} and {@code offerLevels} are pre-sorted in ascending
 * {@code positionNo} order (best price first) by the time they reach this class,
 * so index {@code 0} always represents the best bid or best offer respectively.
 */
@Data
@Builder(toBuilder = true)
@Slf4j
public class BookEntry {

    /**
     * Instrument symbol (FIX tag 55).
     */
    private final String symbol;

    /**
     * Full bid ladder, sorted best → worst (positionNo ascending).
     * Index 0 is the best bid. Empty list if no bid levels were present in the snapshot.
     */
    @Builder.Default
    private final List<PriceLevel> bidLevels = List.of();

    /**
     * Full offer ladder, sorted best → worst (positionNo ascending).
     * Index 0 is the best offer. Empty list if no offer levels were present in the snapshot.
     */
    @Builder.Default
    private final List<PriceLevel> offerLevels = List.of();

    /**
     * Last trade price (MDEntryType=TRADE); {@code 0.0} if unavailable.
     */
    private final double lastTradePrice;

    /**
     * Logical name of the market feed that last updated this entry (e.g. {@code "EBS"}).
     */
    private final String marketName;

    /**
     * Wall-clock instant at which this entry was last written.
     */
    private final Instant lastUpdated;
}
