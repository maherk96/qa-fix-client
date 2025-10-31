# QA FIX Client

Lightweight QuickFIX/J client components for managing one or two FIX sessions (trade and/or quote) per client stream. This project provides:

- A robust connector (`QFConnector`) that encapsulates QuickFIX/J `Application`, session lifecycle, connection waiting, and message sending.
- Configuration model classes (`cfg/*`) to build sessions and environment at runtime without .cfg files.
- Optional listener interfaces to observe inbound/outbound app messages and session events.
- Tests and a tiny in‑JVM test acceptor to validate connectivity and behaviors.

This repo targets Java 17+ and QuickFIX/J 2.3.x.

## Features

- Two-session support (trade and quote) per client stream
- Idempotent, thread-safe lifecycle: `start()`, `stop()`, `restart()`, `restartAndAwait(...)`
- Connection await helpers via timeout or `Duration`
- Safe message sending with session state validation and rich errors
- Optional `PortsConfiguration` to override connection ports per sender (e.g., local dev)
- Optional listeners for inbound, outbound, and session events
- SLF4J logging (Logback included for tests/dev)

## Project Layout

- `src/main/java/com/qa/quick/fix/cfg`: configuration POJOs
  - `CommonSettings`, `ClientDefinition`, `SessionConfig`, `ConnectionEnvironment`, `ConnectionDetails`, `OtherSettings`, `PortsConfiguration`, `ClientPortInfo`
- `src/main/java/com/qa/quick/fix/core/client`: connector and status
  - `QFConnector`, `QFClientStatus`
- `src/main/java/com/qa/quick/fix/core/listeners`: listener interfaces
  - `QFInboundMessageListener`, `QFOutboundMessageListener`, `QFSessionEventListener`
- `src/test/java/com/qa/quick/fix`: integration/unit tests and an in‑JVM acceptor
  - `TestFixAcceptor` (embedded QuickFIX/J acceptor used by tests)
  - `QFConnectorLifecycleIT`, `QFConnectorConnectionStatusIT`
  - Added: `QFConnectorCallbacksTest`, `QFConnectorSendAndAwaitIT`

## Build, Test, Coverage

- Build and test
  - `./gradlew clean test`
- Generate coverage report
  - `./gradlew jacocoTestReport`
  - Open `build/reports/jacoco/test/html/index.html`
- Enforce coverage (bundle-wide): `./gradlew check`
  - Configured in `build.gradle` to require 80% instruction coverage at the bundle level
  - If you want to relax this for local development, comment or adjust the `jacocoTestCoverageVerification` rule

## Quick Start: Creating a Connector

Example: trade-only connector with a local acceptor.

```java
CommonSettings common = new CommonSettings();
common.setBeginString("FIX.4.4");
common.setTargetCompID("SERVER");
common.setConnectionType("initiator");
common.setReconnectInterval("5");
common.setHeartBtInt("30");
common.setStartTime("00:00:00");
common.setEndTime("00:00:00");
common.setUseDataDictionary("N");
common.setSlf4jLogHeartbeats("N");
common.setFileStorePath("target/data/client");

SessionConfig trade = new SessionConfig();
trade.setSenderCompID("CLIENT_T");
trade.setTargetCompID("SERVER");

ClientDefinition def = new ClientDefinition();
def.setTradeSession(trade);

ConnectionDetails details = new ConnectionDetails();
details.setSocketConnectHost("localhost");
details.setSocketConnectPort("9876");

ConnectionEnvironment env = new ConnectionEnvironment();
env.setTrade(details);

QFConnector connector = new QFConnector(
    "CLIENT_STREAM",
    common,
    def,
    env,
    /* portsConfig */ null,
    /* inbound */ null,
    /* session events */ null,
    /* outbound */ null);

connector.start();
boolean connected = connector.waitForConnection(10, TimeUnit.SECONDS);
if (!connected) throw new IllegalStateException("Not connected in time");

// Send an app message after logon
Message app = new Message();
app.getHeader().setString(MsgType.FIELD, MsgType.NEWS);
connector.sendTradeMessage(app);

connector.stop();
```

## Session Types and Status

- Trade session: configured by `ClientDefinition.tradeSession` + `ConnectionEnvironment.trade`
- Quote session: configured by `ClientDefinition.quoteSession` + `ConnectionEnvironment.quote`
- A connector can manage zero, one, or two sessions; `isConnected()` returns true only when all configured sessions are logged on
- Snapshot: `QFClientStatus` exposes stream name, connection booleans, and current `SessionID`s

## Listeners

- `QFInboundMessageListener.onMessage(sessionId, message)` — inbound app messages
- `QFOutboundMessageListener.onOutgoingMessage(sessionId, message)` — outbound app messages
- `QFSessionEventListener.onLogon/onLogout/onReject` — session lifecycle and admin rejects
- Listener exceptions are caught and logged; they won’t disrupt the connector

## Admin Logon Enrichment

If `ClientDefinition.other` is provided, `QFConnector.toAdmin(...)` enriches LOGON messages with:

- Username (553), Password (554)
- DefaultApplVerID (1137), DefaultCstmApplVerID (1408)
- SenderSubID (50), TargetSubID (57)

Values are applied only when present and non-empty in `OtherSettings`.

## Port Overrides (Local Dev)

`PortsConfiguration` can override the `SocketConnectPort` per senderCompId. This is useful for targeting local services without changing upstream config.

```java
PortsConfiguration ports = new PortsConfiguration();
ports.setClients(List.of(new ClientPortInfo("CLIENT_T", "9876", null)));

// Pass the ports into QFConnector constructor; it will prefer the override
```

## Utilities & Tasks

- Spotless formatting: `./gradlew spotlessApply`
- Demo tasks (if present in your classpath):
  - `runExtractor` — runs `com.qa.quick.fix.poc.builder.FIXMessageExtractor` (pass `-Pdict=FIX44.xml` to point a dictionary)
  - `runRepro` — runs `com.qa.quick.fix.poc.demo.ReproMain`

## Testing

- Integration tests spin up an in‑JVM acceptor (`TestFixAcceptor`) to validate logon/logouts and application messaging.
- Additional tests cover:
  - Lifecycle and connection waits
  - Connection status reporting across session configurations
  - Admin/app callbacks and listener behavior
  - Message send success and error paths
  - `PortsConfiguration` overrides

View coverage for the client package at:

- `build/reports/jacoco/test/html/com.qa.quick.fix.core.client/index.html`

## Thread-safety & Lifecycle Notes

- Lifecycle methods (`start/stop/restart/restartAndAwait`) are synchronized and idempotent.
- Connection waiting uses a resettable `CountDownLatch` sized to configured sessions; drained on stop to unblock waiters.
- Message sending validates session existence and logon state and throws `QFSessionException` with a clear reason if it cannot send.

## Logging

- Uses SLF4J (Logback in dev/test). Configure logback via a standard `logback.xml` on the classpath for your environment.

## Requirements

- Java 17+
- Gradle wrapper included
- QuickFIX/J 2.3.x dependencies are declared in `build.gradle`

## Contributing

- Keep changes focused and small. Add or update tests for new behavior.
- Run `./gradlew spotlessApply test jacocoTestReport` before opening a PR.
