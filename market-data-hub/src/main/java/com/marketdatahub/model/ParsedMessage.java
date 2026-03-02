package com.marketdatahub.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Normalised representation of a {@code MarketDataSnapshotFullRefresh} (FIX tag 35=W).
 * <p>
 * Supports full order book depth via the {@code levels} list, which maps
 * directly to the {@code NoMDEntries} (tag 268) repeating group. Each element
 * is a {@link PriceLevel} carrying a side ({@link MDEntryType}), price, size,
 * and 1-based position number.
 * <p>
 * Convenience methods {@link #getBidPrice()}, {@link #getOfferPrice()},
 * {@link #getLastTradePrice()}, {@link #getBidSize()}, and {@link #getOfferSize()}
 * return rung-1 values for backward compatibility with code written against the
 * previous flat model.
 *
 * <h2>Example — building a two-level ladder message</h2>
 * <pre>{@code
 * ParsedMessage msg = ParsedMessage.builder()
 *     .symbol("EUR/USD")
 *     .marketName("EBS")
 *     .timestamp(Instant.now())
 *     .levels(List.of(
 *         PriceLevel.builder().entryType(MDEntryType.BID).price(1.0850).size(1_000_000L).positionNo(1).build(),
 *         PriceLevel.builder().entryType(MDEntryType.BID).price(1.0849).size(2_000_000L).positionNo(2).build(),
 *         PriceLevel.builder().entryType(MDEntryType.OFFER).price(1.0852).size(1_500_000L).positionNo(1).build(),
 *         PriceLevel.builder().entryType(MDEntryType.OFFER).price(1.0853).size(3_000_000L).positionNo(2).build()
 *     ))
 *     .build();
 * }</pre>
 */
@Data
@Builder
public class ParsedMessage {

    /**
     * Instrument symbol (FIX tag 55).
     */
    private String symbol;

    /**
     * All price levels from the {@code NoMDEntries} repeating group, in the order
     * they were received from the transport layer.
     * <p>
     * Use {@link #getBidLevels()} / {@link #getOfferLevels()} for side-filtered,
     * position-sorted views.
     */
    private List<PriceLevel> levels;

    /**
     * Logical market identifier (e.g. {@code "EBS"}).
     */
    private String marketName;

    /**
     * Wall-clock instant at which this message was received.
     */
    private Instant timestamp;

    // -----------------------------------------------------------------------
    // Backward-compatible convenience accessors (rung-1 values)
    // -----------------------------------------------------------------------

    /**
     * Returns the best bid price (rung 1), or {@code 0.0} if no bid levels are present.
     *
     * @return best bid price, or 0.0 if absent
     */
    public double getBidPrice() {
        return getBidLevels().stream()
                .min(Comparator.comparingInt(PriceLevel::getPositionNo))
                .map(PriceLevel::getPrice)
                .orElse(0.0);
    }

    /**
     * Returns the best offer price (rung 1), or {@code 0.0} if no offer levels are present.
     *
     * @return best offer price, or 0.0 if absent
     */
    public double getOfferPrice() {
        return getOfferLevels().stream()
                .min(Comparator.comparingInt(PriceLevel::getPositionNo))
                .map(PriceLevel::getPrice)
                .orElse(0.0);
    }

    /**
     * Returns the last trade price (MDEntryType=TRADE), or {@code 0.0} if not present.
     *
     * @return last trade price, or 0.0 if absent
     */
    public double getLastTradePrice() {
        if (levels == null) return 0.0;
        return levels.stream()
                .filter(l -> l.getEntryType() == MDEntryType.TRADE)
                .map(PriceLevel::getPrice)
                .findFirst()
                .orElse(0.0);
    }

    /**
     * Returns the bid-side quantity at rung 1, or {@code 0} if no bid levels are present.
     *
     * @return best bid size, or 0 if absent
     */
    public long getBidSize() {
        return getBidLevels().stream()
                .min(Comparator.comparingInt(PriceLevel::getPositionNo))
                .map(PriceLevel::getSize)
                .orElse(0L);
    }

    /**
     * Returns the offer-side quantity at rung 1, or {@code 0} if no offer levels are present.
     *
     * @return best offer size, or 0 if absent
     */
    public long getOfferSize() {
        return getOfferLevels().stream()
                .min(Comparator.comparingInt(PriceLevel::getPositionNo))
                .map(PriceLevel::getSize)
                .orElse(0L);
    }

    // -----------------------------------------------------------------------
    // Ladder accessors
    // -----------------------------------------------------------------------

    /**
     * Returns all bid levels sorted by {@code positionNo} ascending (best bid first).
     *
     * @return sorted, unmodifiable list of bid {@link PriceLevel}s; empty list if none
     */
    public List<PriceLevel> getBidLevels() {
        if (levels == null) return List.of();
        return levels.stream()
                .filter(l -> l.getEntryType() == MDEntryType.BID)
                .sorted(Comparator.comparingInt(PriceLevel::getPositionNo))
                .toList();
    }

    /**
     * Returns all offer levels sorted by {@code positionNo} ascending (best offer first).
     *
     * @return sorted, unmodifiable list of offer {@link PriceLevel}s; empty list if none
     */
    public List<PriceLevel> getOfferLevels() {
        if (levels == null) return List.of();
        return levels.stream()
                .filter(l -> l.getEntryType() == MDEntryType.OFFER)
                .sorted(Comparator.comparingInt(PriceLevel::getPositionNo))
                .toList();
    }
}
