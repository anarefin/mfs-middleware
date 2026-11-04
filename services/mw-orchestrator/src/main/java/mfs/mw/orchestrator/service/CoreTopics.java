package mfs.mw.orchestrator.service;

import java.util.Set;

/**
 * Which core topic a command goes on (spec 6.3, M2 gate decision, ticket 50): {@code HoldFunds},
 * {@code CommitHold} and {@code ReleaseHold} (transactions with an external call) on the core's
 * Digital Payment side {@code core.payment.command.v1}, every other command on
 * {@code core.wallet.command.v1}. The core answers on the event topic paired with the command
 * topic, so results are consumed from both event topics.
 */
public final class CoreTopics {

  public static final String WALLET_COMMANDS = "core.wallet.command.v1";
  public static final String PAYMENT_COMMANDS = "core.payment.command.v1";
  public static final String WALLET_EVENTS = "core.wallet.event.v1";
  public static final String PAYMENT_EVENTS = "core.payment.event.v1";

  private static final Set<String> PAYMENT_COMMAND_TYPES = Set.of("HoldFunds", "CommitHold", "ReleaseHold");

  private CoreTopics() {}

  public static String commandTopic(String commandType) {
    return PAYMENT_COMMAND_TYPES.contains(commandType) ? PAYMENT_COMMANDS : WALLET_COMMANDS;
  }
}
