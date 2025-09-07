import lombok.extern.slf4j.Slf4j;
import quickfix.FieldNotFound;
import quickfix.Group;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.BidPx;
import quickfix.field.BidSize;
import quickfix.field.NoRelatedSym;
import quickfix.field.OfferPx;
import quickfix.field.OfferSize;
import quickfix.field.QuoteCancelType;
import quickfix.field.QuoteID;
import quickfix.field.QuoteReqID;
import quickfix.field.QuoteStatus;
import quickfix.field.QuoteType;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.fix44.Quote;
import quickfix.fix44.QuoteCancel;
import quickfix.fix44.QuoteRequest;
import quickfix.fix44.QuoteStatusReport;

import java.util.concurrent.atomic.AtomicLong; /**
 * Handles quote-related FIX messages
 */
@Slf4j
public class QuoteMessageHandler {

    private final AtomicLong quoteIdCounter;
    
    public QuoteMessageHandler(AtomicLong quoteIdCounter) {
        this.quoteIdCounter = quoteIdCounter;
    }
    
    public void handleMessage(Message message, SessionID sessionId) throws FieldNotFound {
        if (message instanceof QuoteRequest) {
            handleQuoteRequest((QuoteRequest) message, sessionId);
        } else if (message instanceof QuoteCancel) {
            handleQuoteCancel((QuoteCancel) message, sessionId);
        } else {
            log.warn("Unsupported quote message type: {}", message.getClass().getSimpleName());
        }
    }
    
    private void handleQuoteRequest(QuoteRequest quoteRequest, SessionID sessionId) throws FieldNotFound {
        String quoteReqID = quoteRequest.getQuoteReqID().getValue();
        
        log.info("Processing quote request: QuoteReqID={}", quoteReqID);
        
        // Extract symbols from quote request
        int noRelatedSym = quoteRequest.getInt(NoRelatedSym.FIELD);
        
        for (int i = 1; i <= noRelatedSym; i++) {
            Group symbolGroup = quoteRequest.getGroup(i, NoRelatedSym.FIELD);
            String symbol = symbolGroup.getString(Symbol.FIELD);
            
            // Generate random bid/offer prices
            sendQuote(sessionId, quoteReqID, symbol);
        }
    }
    
    private void handleQuoteCancel(QuoteCancel quoteCancel, SessionID sessionId) throws FieldNotFound {
        String quoteCancelType = quoteCancel.getString(QuoteCancelType.FIELD);
        log.info("Processing quote cancel: Type={}", quoteCancelType);
        
        QuoteStatusReport statusReport = new QuoteStatusReport();
        statusReport.set(new QuoteID(String.valueOf(quoteIdCounter.getAndIncrement())));
        statusReport.set(new QuoteStatus(QuoteStatus.CANCELED));
        statusReport.set(new Text("Quote cancelled"));
        
        sendMessage(statusReport, sessionId);
    }
    
    private void sendQuote(SessionID sessionId, String quoteReqID, String symbol) {
        try {
            Quote quote = new Quote();
            quote.set(new QuoteID(String.valueOf(quoteIdCounter.getAndIncrement())));
            if (quoteReqID != null) {
                quote.set(new QuoteReqID(quoteReqID));
            }
            quote.set(new Symbol(symbol));
            quote.set(new BidPx(2.25)); // Example bid price
            quote.set(new BidSize(2.25));
            quote.set(new OfferPx(2.25));
            quote.set(new OfferSize(2.25));
            quote.set(new QuoteType(QuoteType.TRADEABLE));
            
            sendMessage(quote, sessionId);
            log.info("Sent quote: Symbol={}", symbol);
        } catch (Exception e) {
            log.error("Error sending quote", e);
        }
    }
    
    private void sendMessage(Message message, SessionID sessionId) {
        try {
            Session.sendToTarget(message, sessionId);
        } catch (SessionNotFound e) {
            log.error("Session not found when sending message", e);
        }
    }

}
