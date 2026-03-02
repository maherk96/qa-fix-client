package com.marketdatahub.config;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

/**
 * Immutable configuration for a Solace PubSub+ market feed.
 *
 * <pre>{@code
 * SolaceConfig cfg = SolaceConfig.builder()
 *     .host("tcp://broker:55555")
 *     .vpn("default")
 *     .username("user")
 *     .password("pass")
 *     .topic("EQ/marketData/v1/EBS/>")
 *     .build();
 * }</pre>
 */
@Getter
@Builder
@ToString(exclude = "password")
public class SolaceConfig implements MarketConfig {

    /** SMF host URL, e.g. {@code tcp://broker:55555}. */
    @NonNull
    private final String host;

    /** Solace VPN name. */
    @NonNull
    private final String vpn;

    /** Client username. */
    @NonNull
    private final String username;

    /** Client password (excluded from {@code toString}). */
    @NonNull
    private final String password;

    /** Solace topic subscription string (supports wildcards with {@code >}). */
    @NonNull
    private final String topic;

    /**
     * {@inheritDoc}
     */
    @Override
    public TransportType getTransportType() {
        return TransportType.SOLACE;
    }
}
