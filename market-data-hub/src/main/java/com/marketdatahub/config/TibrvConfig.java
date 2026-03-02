package com.marketdatahub.config;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

/**
 * Immutable configuration for a TIBCO Rendezvous (TIBRV) market feed.
 *
 * <pre>{@code
 * TibrvConfig cfg = TibrvConfig.builder()
 *     .service("7500")
 *     .network(";239.255.0.1")
 *     .daemon("localhost:7500")
 *     .subject("REUTERS.>")
 *     .build();
 * }</pre>
 */
@Getter
@Builder
@ToString
public class TibrvConfig implements MarketConfig {

    /** TIBRV service name or port number. */
    @NonNull
    private final String service;

    /**
     * Network interface / multicast address.
     * A leading semicolon selects the default interface with a specific multicast group,
     * e.g. {@code ";239.255.0.1"}.
     */
    @NonNull
    private final String network;

    /** TIBRV daemon address, e.g. {@code "localhost:7500"}. */
    @NonNull
    private final String daemon;

    /** Subject subscription pattern (supports wildcards with {@code >} and {@code *}). */
    @NonNull
    private final String subject;

    /**
     * {@inheritDoc}
     */
    @Override
    public TransportType getTransportType() {
        return TransportType.TIBRV;
    }
}
