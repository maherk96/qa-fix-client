package com.marketdatahub.model;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a single price level (rung) from the {@code NoMDEntries} repeating group
 * of a FIX {@code MarketDataSnapshotFullRefresh} (tag 35=W) message.
 *
 * <ul>
 *   <li>{@code entryType}  — MDEntryType (tag 269): {@link MDEntryType#BID BID},
 *       {@link MDEntryType#OFFER OFFER}, or {@link MDEntryType#TRADE TRADE}</li>
 *   <li>{@code price}      — MDEntryPx (tag 270)</li>
 *   <li>{@code size}       — MDEntrySize (tag 271)</li>
 *   <li>{@code positionNo} — MDEntryPositionNo (tag 290), 1-based rung index
 *       where 1 is the best (tightest) price</li>
 * </ul>
 */
@Data
@Builder
public class PriceLevel {

    /** Side and type of this entry — BID, OFFER, or TRADE. */
    private MDEntryType entryType;

    /** Price of this rung (MDEntryPx, tag 270). */
    private double price;

    /** Quantity available at this rung (MDEntrySize, tag 271). */
    private long size;

    /**
     * 1-based position number (MDEntryPositionNo, tag 290).
     * Rung 1 is always the best (closest to mid) price.
     */
    private int positionNo;
}
