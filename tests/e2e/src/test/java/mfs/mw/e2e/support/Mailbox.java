package mfs.mw.e2e.support;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The dev mail server (GreenMail, ticket 25) through the dev-only APISIX route {@code /test/mail/*}
 * ({@code deploy/apisix/routes-test/test-mail.json} -> GreenMail's REST API {@code /api/*}):
 * {@code GET /api/user/{email}/messages/} lists what mw-notify sent to that address over SMTP. A
 * mailbox that never received mail does not exist yet (400): no messages.
 */
public final class Mailbox {

  /** One received email: decoded subject, MIME content type, the raw MIME message. */
  public record Mail(String messageId, String subject, String contentType, String mime) {}

  private Mailbox() {}

  public static List<Mail> messagesTo(String email) {
    Http.Response r =
        Http.get(Stack.get().url("test/mail/user/" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "/messages/"),
            Map.of("apikey", Stack.get().devCredential("MW_E2E_API_KEY")));
    if (r.status() == 400) {
      return List.of();
    }
    if (r.status() != 200) {
      throw new IllegalStateException("mail server query failed: " + r);
    }
    List<Mail> out = new ArrayList<>();
    for (JsonNode m : r.body()) {
      out.add(new Mail(m.path("Message-ID").asString(), m.path("subject").asString(),
          m.path("contentType").asString(), m.path("mimeMessage").asString()));
    }
    return out;
  }
}
