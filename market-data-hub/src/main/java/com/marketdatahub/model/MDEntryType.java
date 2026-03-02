package com.marketdatahub.model;

/**
 * Represents the FIX MDEntryType (tag 269) values supported by market-data-hub.
 */
public enum MDEntryType {

    /** MDEntryType = 0 — bid side of the order book. */
    BID,

    /** MDEntryType = 1 — offer (ask) side of the order book. */
    OFFER,

    /** MDEntryType = 2 — last trade price entry. */
    TRADE
}
