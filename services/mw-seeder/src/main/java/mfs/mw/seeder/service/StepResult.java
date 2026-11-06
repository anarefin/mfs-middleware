package mfs.mw.seeder.service;

/**
 * What one step did: how many things it created, updated (e.g. roles added, a partner put back to
 * its baseline) and found already as seeded. A second run of the seeder is all {@code UNCHANGED}.
 */
public record StepResult(int created, int updated, int unchanged) {

  public enum Outcome {
    CREATED,
    UPDATED,
    UNCHANGED
  }

  public static StepResult created(int n) {
    return new StepResult(n, 0, 0);
  }

  public static StepResult updated(int n) {
    return new StepResult(0, n, 0);
  }

  public static StepResult unchanged(int n) {
    return new StepResult(0, 0, n);
  }

  public StepResult plus(StepResult other) {
    return new StepResult(created + other.created, updated + other.updated, unchanged + other.unchanged);
  }

  /** CREATED if anything was created, else UPDATED if anything changed, else UNCHANGED. */
  public Outcome outcome() {
    if (created > 0) {
      return Outcome.CREATED;
    }
    return updated > 0 ? Outcome.UPDATED : Outcome.UNCHANGED;
  }

  @Override
  public String toString() {
    return "created=" + created + " updated=" + updated + " unchanged=" + unchanged;
  }
}
