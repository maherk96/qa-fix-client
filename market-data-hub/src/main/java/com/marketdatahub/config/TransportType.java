package com.marketdatahub.config;

/**
 * Enumeration of the supported transport backends for a market feed.
 */
public enum TransportType {

    /** Solace PubSub+ message broker transport. */
    SOLACE,

    /** TIBCO Rendezvous (TIBRV) transport. */
    TIBRV
}
