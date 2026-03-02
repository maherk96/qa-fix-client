package com.marketdatahub.feed;

/**
 * Operational status of a single {@link MarketFeed} as reported by
 * {@link FeedManager#getStatus()}.
 */
public enum FeedStatus {

    /** The feed has been started and is actively receiving messages. */
    RUNNING,

    /** The feed has been stopped or has not yet been started. */
    STOPPED,

    /**
     * The feed encountered an unrecoverable error and is no longer delivering messages.
     * Manual intervention (stop + start) is required.
     */
    ERROR
}
