package com.qa.quick.fix.poc.demo;

import com.qa.quick.fix.poc.client.MessageListener;
import com.qa.quick.fix.poc.mock.SimpleFixAcceptor;
import com.qa.quick.fix.poc.pool.FixClientPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.NewOrderSingle;

import java.util.Collections;
import java.util.Date;
import java.util.Random;
import java.util.UUID;

/**
 * Demo entrypoint: starts a local FIX acceptor and a client via FixClientPoolManager,
 * sends 5 orders and logs received ExecutionReports to help reproduce duplication.
 */
public class ReproMain {
    private static final Logger log = LoggerFactory.getLogger(ReproMain.class);

    public static void main(String[] args) throws Exception {
        int port = 9876;
        String begin = "FIX.4.4";
        String serverComp = "SERVER";
        String clientComp = "CLIENT1";

        // Start simple acceptor
        SimpleFixAcceptor acceptor = new SimpleFixAcceptor(begin, serverComp, clientComp, port);
        acceptor.start();

        // Prepare pool with one client
        FixClientPoolManager pool = new FixClientPoolManager(
                "demo-config.json",
                "LOCAL",
                Collections.singleton("CLIENT1")
        );

        // Attach a listener to log inbound ERs with MsgSeqNum and PossDupFlag
        pool.setGlobalMessageListener((sessionId, message) -> {
            try {
                String msgType = message.getHeader().getString(MsgType.FIELD);
                int seq = message.getHeader().getInt(MsgSeqNum.FIELD);
                boolean possDup = message.getHeader().isSetField(PossDupFlag.FIELD) && message.getHeader().getBoolean(PossDupFlag.FIELD);
                log.info("IN [{}] {} Seq={} PossDup={} Body={}", sessionId, msgType, seq, possDup, message);
            } catch (FieldNotFound e) {
                log.info("IN [{}] {} (header parse error) Body={}", sessionId, message.getClass().getSimpleName(), message);
            }
        });

        pool.startAll();

        // Wait a moment for full logon
        Thread.sleep(1000);

        // Send 5 orders
        for (int i = 0; i < 20; i++) {
            NewOrderSingle nos = buildNos(clientComp, serverComp);
            pool.sendTradeMessage("CLIENT1", nos);
            Thread.sleep(20);
        }

        // Allow time to receive ERs
        Thread.sleep(2000);

        // Cleanup
        pool.stopAll();
        acceptor.stop();
    }

    private static NewOrderSingle buildNos(String sender, String target) {
        char side = new Random().nextBoolean() ? Side.BUY : Side.SELL;
        double qty = 100.0;
        double price = 101.25;
        String symbol = "AAPL";

        NewOrderSingle nos = new NewOrderSingle(
                new ClOrdID(UUID.randomUUID().toString()),
                new Side(side),
                new TransactTime(java.time.LocalDateTime.now()),
                new OrdType(OrdType.LIMIT)
        );
        nos.set(new OrderQty(qty));
        nos.set(new Price(price));
        nos.set(new Symbol(symbol));
        nos.getHeader().setString(SenderCompID.FIELD, sender);
        nos.getHeader().setString(TargetCompID.FIELD, target);
        nos.getHeader().setString(BeginString.FIELD, "FIX.4.4");
        return nos;
    }
}
