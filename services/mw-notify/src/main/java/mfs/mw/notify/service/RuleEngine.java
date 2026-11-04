package mfs.mw.notify.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.dto.TxnStatusEvent;
import mfs.mw.notify.dto.TxnStatusEvent.Amount;
import mfs.mw.notify.dto.TxnStatusEvent.Party;
import mfs.mw.notify.entity.RuleRecord;
import mfs.mw.notify.util.MaskingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * Evaluates {@code notify.rule} rows against a {@code mw.txn.status.v1} event (spec 10.3). A
 * condition is a SpEL expression run in a {@link SimpleEvaluationContext} with read-only access to
 * the event's facts ({@link TxnFacts}): no type references, constructors, bean references or
 * method calls on arbitrary types, so a rule cannot reach outside the event. A condition that does
 * not parse, fails or is not boolean never matches. Each match becomes a {@link
 * NotificationRequest} with messageId {@code <txnId>:<channel>:<ruleId>}, so a repeated status
 * event yields the same ids and is dropped as a duplicate. The other party's id is masked in the
 * params (18.3). Params: amount, fee, txnId, txnType, counterparty, coreRef, partnerRef, partner
 * (partnerId), biller (billerCode, else partnerId), errorCode.
 */
public class RuleEngine {

  private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

  private static final Pattern MSISDN = Pattern.compile("^(?:\\+?880|0)1[3-9]\\d{8}$");

  private final SpelExpressionParser parser = new SpelExpressionParser();
  private final Map<String, Optional<Expression>> parsed = new ConcurrentHashMap<>();
  private final String defaultLocale;

  public RuleEngine(String defaultLocale) {
    this.defaultLocale = defaultLocale;
  }

  /** Who to email: a party's registered address (spec 10.2), or none. */
  @FunctionalInterface
  public interface ContactLookup {
    ContactLookup NONE = id -> Optional.empty();

    Optional<String> emailOf(String partyId);
  }

  public List<NotificationRequest> requestsFor(TxnStatusEvent event, List<RuleRecord> rules) {
    return requestsFor(event, rules, ContactLookup.NONE);
  }

  /**
   * The requests of every matching rule. An SMS rule needs an MSISDN-shaped recipient id; an EMAIL
   * rule needs a registered address for the recipient ({@code contacts}); otherwise the rule is
   * skipped.
   */
  public List<NotificationRequest> requestsFor(TxnStatusEvent event, List<RuleRecord> rules, ContactLookup contacts) {
    TxnFacts facts = new TxnFacts(event);
    EvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().withRootObject(facts).build();
    List<NotificationRequest> out = new ArrayList<>();
    for (RuleRecord rule : rules) {
      if (!matches(rule, ctx)) {
        continue;
      }
      Party recipient = party(event, rule.recipient());
      if (recipient == null || recipient.id() == null) {
        continue;
      }
      NotificationRequest.Recipient address;
      if (Channel.EMAIL.name().equals(rule.channel())) {
        Optional<String> email = contacts.emailOf(recipient.id());
        if (email.isEmpty()) {
          continue;
        }
        address = new NotificationRequest.Recipient(null, email.get(), null, null);
      } else if (MSISDN.matcher(recipient.id()).matches()) {
        address = new NotificationRequest.Recipient(recipient.id(), null, null, null);
      } else {
        continue;
      }
      out.add(request(event, rule, address, counterparty(event, rule.recipient())));
    }
    return out;
  }

  /**
   * The other side of the transaction for the recipient: the debit party for the credit party, the
   * credit party for the debit party; for the initiator the side that is not the initiator itself
   * (an ADD_MONEY_BANK initiator is credited, so its counterparty is the bank account).
   */
  private static Party counterparty(TxnStatusEvent e, String role) {
    return switch (role) {
      case "CREDIT_PARTY" -> e.debitParty();
      case "INITIATOR" -> {
        String self = e.initiator() == null ? null : e.initiator().id();
        boolean credited = e.creditParty() != null && self != null && self.equals(e.creditParty().id());
        yield credited ? e.debitParty() : e.creditParty();
      }
      default -> e.creditParty();
    };
  }

  private boolean matches(RuleRecord rule, EvaluationContext ctx) {
    Optional<Expression> expr = parsed.computeIfAbsent(rule.condition(), this::parse);
    if (expr.isEmpty()) {
      return false;
    }
    try {
      return Boolean.TRUE.equals(expr.get().getValue(ctx, Boolean.class));
    } catch (ExpressionException e) {
      log.atWarn().addKeyValue("event", "notify.rule.failed").addKeyValue("ruleId", rule.ruleId())
          .addKeyValue("errorClass", e.getClass().getName()).log("rule condition could not be evaluated");
      return false;
    }
  }

  private Optional<Expression> parse(String condition) {
    try {
      return Optional.of(parser.parseExpression(condition));
    } catch (ExpressionException e) {
      log.atWarn().addKeyValue("event", "notify.rule.invalid").log("rule condition does not parse");
      return Optional.empty();
    }
  }

  private NotificationRequest request(TxnStatusEvent e, RuleRecord rule, NotificationRequest.Recipient recipient,
      Party other) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("amount", money(e.amount()));
    params.put("fee", money(e.fee()));
    params.put("txnId", e.txnId());
    params.put("txnType", e.txnType());
    if (other != null && other.id() != null) {
      params.put("counterparty", MaskingPolicy.maskPartyId(other.type(), other.id()));
    }
    if (e.coreRef() != null) {
      params.put("coreRef", e.coreRef());
    }
    if (e.partnerRef() != null) {
      params.put("partnerRef", e.partnerRef());
    }
    if (e.partner() != null && e.partner().partnerId() != null) {
      params.put("partner", e.partner().partnerId());
      String biller = e.partner().billerCode();
      params.put("biller", biller == null || biller.isBlank() ? e.partner().partnerId() : biller);
    }
    if (e.error() != null && e.error().code() != null) {
      params.put("errorCode", e.error().code());
    }
    Channel channel = Channel.valueOf(rule.channel());
    return new NotificationRequest(1, e.txnId() + ":" + channel.name().toLowerCase(Locale.ROOT) + ":" + rule.ruleId(),
        e.txnId(), e.correlationId(), recipient,
        rule.templateCode(), e.locale() == null ? defaultLocale : e.locale(), params, List.of(channel),
        Priority.valueOf(rule.priority()), null, null);
  }

  private static Party party(TxnStatusEvent e, String role) {
    return switch (role) {
      case "INITIATOR" -> e.initiator();
      case "DEBIT_PARTY" -> e.debitParty();
      case "CREDIT_PARTY" -> e.creditParty();
      default -> null;
    };
  }

  /** Minor units as taka with two decimals ({@code 50000} -> {@code 500.00}). */
  static String money(Amount amount) {
    long minor = amount == null ? 0 : amount.valueMinor();
    return String.format(Locale.ROOT, "%d.%02d", minor / 100, minor % 100);
  }

  /** What a rule condition can read (read-only getters). */
  public static final class TxnFacts {

    private final TxnStatusEvent e;

    TxnFacts(TxnStatusEvent e) {
      this.e = e;
    }

    public String getStatus() {
      return e.status();
    }

    public String getTxnType() {
      return e.txnType();
    }

    public String getChannel() {
      return e.channel();
    }

    public long getAmountMinor() {
      return e.amount() == null ? 0 : e.amount().valueMinor();
    }

    public String getInitiatorId() {
      return e.initiator() == null ? null : e.initiator().id();
    }

    public String getDebitPartyId() {
      return e.debitParty() == null ? null : e.debitParty().id();
    }

    public String getCreditPartyId() {
      return e.creditParty() == null ? null : e.creditParty().id();
    }

    public String getDebitPartyType() {
      return e.debitParty() == null ? null : e.debitParty().type();
    }

    public String getCreditPartyType() {
      return e.creditParty() == null ? null : e.creditParty().type();
    }
  }
}
