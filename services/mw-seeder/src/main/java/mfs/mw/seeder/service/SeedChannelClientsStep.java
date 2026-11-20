package mfs.mw.seeder.service;

import java.util.Optional;
import mfs.mw.seeder.client.AdminConsole;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.config.SeederProperties.ChannelClientSpec;
import mfs.mw.seeder.dto.ChannelClientChangeRequest;
import mfs.mw.seeder.dto.ChannelClientView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * seed-channel-clients (ticket 100a): every client of {@code mw.seeder.channel-clients} (today's edge
 * clients: the app types, the four MNO USSD gateways, the partners, the checkout merchants, the org
 * portal) as a row of mw-admin-api's channel client registry, through maker-checker: a missing or
 * different client is filed (UPSERT with the full definition) by the seed maker and approved by the
 * seed checker; mw-admin-api then projects the BASIC ones (the MNO gateways) to APISIX. A client at
 * its baseline is left alone. Pending requests of the seed maker are approved first; one of another
 * operator fails the step.
 */
@Service
@Order(6)
public class SeedChannelClientsStep implements SeedStep {

  static final String REASON = "seed: channel client baseline (mw-seeder)";
  static final String TYPE = "channel_client";

  private static final Logger log = LoggerFactory.getLogger(SeedChannelClientsStep.class);

  private final AdminConsole admin;
  private final MakerChecker ops;
  private final SeederProperties props;

  public SeedChannelClientsStep(AdminConsole admin, SeederProperties props) {
    this.admin = admin;
    this.ops = new MakerChecker(admin, props.operators());
    this.props = props;
  }

  @Override
  public String id() {
    return "seed-channel-clients";
  }

  @Override
  public StepResult run(SeedContext ctx) {
    StepResult result = StepResult.unchanged(0);
    for (ChannelClientSpec spec : props.channelClients()) {
      int leftovers =
          ops.approveLeftovers(admin.pendingRequests(ops.checker(), TYPE, spec.clientId()), "channel client " + spec.clientId());
      Optional<ChannelClientView> current = admin.channelClient(ops.checker(), spec.clientId());
      boolean differs = current.isEmpty() || !current.get().definition().equals(spec.definition());
      if (differs) {
        ops.approve(admin.requestChannelClientChange(
            ops.maker(), spec.clientId(), ChannelClientChangeRequest.upsert(spec.definition(), REASON)));
        log.atInfo()
            .addKeyValue("event", "seed.channel_client." + (current.isEmpty() ? "created" : "updated"))
            .addKeyValue("clientId", spec.clientId())
            .log("channel client {} at its baseline", spec.clientId());
      }
      result = result.plus(
          current.isEmpty() && differs ? StepResult.created(1)
              : differs || leftovers > 0 ? StepResult.updated(1) : StepResult.unchanged(1));
    }
    return result;
  }
}
