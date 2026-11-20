package mfs.mw.simulators.dto;

/**
 * One participant of the simulated NPSB switch (ticket 121, seed {@code seed/v1/npsb/participants.json}): a
 * bank or an MFS provider, by its 8-character id. A suspended participant ({@code active} false) is listed
 * but refuses transfers.
 */
public record NpsbParticipant(String id, String name, Kind kind, boolean active) {

  /** What the participant is. */
  public enum Kind {
    BANK,
    MFS
  }
}
