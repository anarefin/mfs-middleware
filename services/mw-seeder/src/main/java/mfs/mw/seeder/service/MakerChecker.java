package mfs.mw.seeder.service;

import java.util.List;
import mfs.mw.seeder.client.AdminConsole;
import mfs.mw.seeder.config.SeederProperties.Operators;
import mfs.mw.seeder.dto.ChangeRequestView;

/**
 * The seeder's two ops personas acting on mw-admin-api: the maker asks, the checker (a different
 * user) approves. Shared by seed-partner-configs and seed-admin-content.
 */
final class MakerChecker {

  private final AdminConsole admin;
  private final Operators ops;

  MakerChecker(AdminConsole admin, Operators ops) {
    this.admin = admin;
    this.ops = ops;
  }

  String maker() {
    return ops.maker();
  }

  String checker() {
    return ops.checker();
  }

  /** The checker approves; anything but APPROVED fails the step. */
  void approve(ChangeRequestView request) {
    ChangeRequestView approved = admin.approve(ops.checker(), request.id());
    if (!"APPROVED".equals(approved.status())) {
      throw new IllegalStateException(
          request.resourceType() + " " + request.resourceId() + ": change request is " + approved.status() + " after approval");
    }
  }

  /**
   * Approves the open requests on a resource that the seed maker left behind (a run that stopped
   * between asking and approving) and returns how many; a request of another operator fails the step,
   * as the admin allows one open request per resource and the seeder never decides someone else's.
   */
  int approveLeftovers(List<ChangeRequestView> pending, String what) {
    for (ChangeRequestView p : pending) {
      if (!ops.maker().equals(p.makerId())) {
        throw new IllegalStateException(
            what + " has an open change request of " + p.makerId() + ": decide it in the console, then seed again");
      }
    }
    pending.forEach(this::approve);
    return pending.size();
  }
}
