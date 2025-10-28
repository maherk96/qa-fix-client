# QA FIX Client

A small, thread‑safe QuickFIX/J client pool for starting, monitoring, and sending messages across one or more FIX initiator sessions. It supports dual‑session clients (trade and quote), round‑robin routing, lifecycle management, and simple health/stats queries.

## Features
- Multiple clients in a pool, each with trade and/or quote sessions
- Start, stop, restart (idempotent, synchronized)
- Await connection for all configured sessions
- Per‑client and global listeners (inbound, outbound, session events)
- Send to a specific client or round‑robin across connected clients
- Connection/status snapshots and basic statistics
- Two configuration modes for ports:
  - Inline `SocketConnectPort` in the main config
  - Separate `ports.json` mapping by `SenderCompID`

## Requirements
- Java 17+ (tested with Java 21)
- Gradle 8+

## Getting Started

### 1) Add a configuration JSON (class path or file)

Main config (`demo-trade-only.json` example):
```
{
  "common": {
    "ConnectionType": "initiator",
    "ReconnectInterval": "1",
    "FileStorePath": "store",
    "StartTime": "00:00:00",
    "EndTime": "00:00:00",
    "UseDataDictionary": "N",
    "BeginString": "FIX.4.4",
    "HeartBtInt": "30",
    "TargetCompID": "SERVER"
  },
  "connections": {
    "LOCAL": {
      "trade": {
        "SocketConnectHost": "127.0.0.1",
        "SocketConnectPort": "9876"  // Option A: inline port
      }
    }
  },
  "clients": {
    "CLIENT1": {
      "tradeSession": {
        "SenderCompID": "CLIENT1",
        "TargetCompID": "SERVER"
      }
    }
  }
}
```

Dual‑session example (`demo-trade-quote.json`):
```
{
  "common": { "ConnectionType": "initiator", "BeginString": "FIX.4.4", "HeartBtInt": "30", "StartTime": "00:00:00", "EndTime": "00:00:00", "FileStorePath": "store" },
  "connections": {
    "TEST": {
      "trade": { "SocketConnectHost": "localhost" },
      "quote": { "SocketConnectHost": "localhost" }
    }
  },
  "clients": {
    "trap_client": {
      "tradeSession": { "SenderCompID": "TRAP-A-001-TRADE" },
      "quoteSession": { "SenderCompID": "TRAP-A-001-QUOTE" }
    }
  }
}
```

Optional `ports.json` (Option B: external ports by `SenderCompID`):
```
{
  "clients": [
    { "name": "TRAP-A-001-QUOTE", "port": "36112", "location": "TEST" },
    { "name": "TRAP-A-001-TRADE", "port": "46112", "location": "TEST" }
  ]
}
```
How ports are resolved:
- If `ports.json` is provided and a `clients[].name` matches the session `SenderCompID`, that port is used.
- Otherwise, if `SocketConnectPort` exists in the main config, it is used.

### 2) Start a pool from JSON files
```
Set<String> clients = Set.of("CLIENT1", "CLIENT2");
QFClientPoolManager pool = new QFClientPoolManager(
    "demo-trade-only.json",   // classpath or file path
    "ports.json",             // optional (null if inlined ports)
    "LOCAL",                  // environment key from config
    clients
);

// Start and wait for connections
pool.startAll();

// Send to a specific client
pool.sendTradeMessage("CLIENT1", new quickfix.fix44.Heartbeat());

// Or round‑robin across all connected trade clients
pool.sendTradeMessage(new quickfix.fix44.Heartbeat());

// Query status and stats
boolean connected = pool.isClientConnected("CLIENT1");
PoolStatistics stats = pool.getStatistics();

// Restart a client
pool.restartClient("CLIENT1", 10, TimeUnit.SECONDS);

// Stop one or all
pool.stopClient("CLIENT2", 10, TimeUnit.SECONDS);
pool.stopAll();
```

### 3) Or build configuration programmatically
```
CommonSettings common = new CommonSettings();
common.setConnectionType("initiator");
common.setBeginString("FIX.4.4");
common.setHeartBtInt("30");
common.setStartTime("00:00:00");
common.setEndTime("00:00:00");
common.setFileStorePath("build/qf-store");

ConnectionEnvironment env = new ConnectionEnvironment();
env.setTrade(new ConnectionDetails("127.0.0.1", "9876"));

ClientDefinition def = new ClientDefinition();
def.setTradeSession(new SessionConfig("CLIENT1", "SERVER"));

FixClientConfiguration cfg = new FixClientConfiguration();
cfg.setCommon(common);
cfg.setConnections(Map.of("LOCAL", env));
cfg.setClients(Map.of("CLIENT1", def));

QFClientPoolManager pool = new QFClientPoolManager(cfg, "LOCAL", Set.of("CLIENT1"));
pool.startAll();
```

## Listeners
Set global listeners on the pool before starting to observe traffic and events:
```
pool.setGlobalMessageListener((sessionId, msg) -> { /* inbound app msgs */ });
pool.setGlobalOutboundMessageListener((sessionId, msg) -> { /* outbound app msgs */ });
pool.setGlobalSessionEventListener(new QFSessionEventListener() {
  public void onLogon(SessionID id) { /* logon */ }
  public void onLogout(SessionID id) { /* logout */ }
  public void onReject(SessionID id, String reason) { /* admin reject */ }
});
```
Listener exceptions are caught and logged so they don’t break FIX engine threads.

## Connection Semantics
- A client is “connected” only when all configured sessions (trade and/or quote) are logged on.
- `QFConnector.awaitConnected(Duration)` and `waitForConnection(timeout, unit)` complete when all configured sessions log on.
- `hasQuoteSession()` indicates capability (configured), not connection state.

## Exceptions
- `QFSessionException`: runtime session state issues (e.g., sending when not logged on).
- `QFInitializationException`: initialization failures in earlier flows.
- `QFClientPoolException`: pool‑level failures (bad config, lifecycle misuse, etc.).

## Thread‑Safety
- Connector start/stop/restart are synchronized and idempotent.
- `sendTradeMessage` / `sendQuoteMessage` resolve the current `Session` atomically and check `isLoggedOn()`.
- Pool `stopClient` and `restartClient` are synchronized; stop removes the client from the pool before shutdown begins.

## Building and Testing
- Run tests: `./gradlew test`
- Coverage (JaCoCo): `build/reports/jacoco/test/html/index.html` (80% minimum enforced)

Integration tests spin up in‑process acceptors for round‑trip verification:
- `QFClientPoolManagerIT` (single trade client)
- `QFClientPoolManagerDualIT` (dual trade+quote client)
- `QFClientPoolManagerMultiIT` (3 trade clients; uses ports mapping)

## Troubleshooting
- Ensure `StartTime` and `EndTime` are set in `common` (QuickFIX/J schedule requires them).
- `SenderCompID` in the client session must match the one you map in `ports.json`.
- If you inline `SocketConnectPort`, you can omit `ports.json`.
- Check logs for QuickFIX/J categories configured in `common` (SLF4J/Logback included).

## License
This project uses QuickFIX/J under its respective license. No additional license is specified for this codebase.
