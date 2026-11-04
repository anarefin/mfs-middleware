package mfs.mw.notify.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.dto.SmsPart;
import mfs.mw.notify.dto.SubmitResult;
import mfs.mw.notify.exception.SmsSendException;

/** Test double at the SMSC seam: records every part and answers with scripted failures. */
public class FakeSmsSender implements SmsSender {

  /** One recorded submit_sm. */
  public record Sent(Priority priority, SmsPart part, String providerMessageId) {}

  private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
  private final Deque<SmsSendException> failures = new ArrayDeque<>();
  private final AtomicInteger ids = new AtomicInteger();
  // Unique per instance: test contexts with their own fake share one database (segment ids are unique).
  private final String prefix = "fake-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "-";

  @Override
  public synchronized SubmitResult submit(Priority priority, SmsPart part) throws SmsSendException {
    SmsSendException failure = failures.poll();
    if (failure != null) {
      throw failure;
    }
    String id = prefix + ids.incrementAndGet();
    sent.add(new Sent(priority, part, id));
    return new SubmitResult("fake-" + priority.name().toLowerCase(java.util.Locale.ROOT), id);
  }

  /** The next {@code n} submits fail with {@code e}. */
  public synchronized void failNext(int n, SmsSendException e) {
    for (int i = 0; i < n; i++) {
      failures.add(e);
    }
  }

  public synchronized List<Sent> sent() {
    return List.copyOf(sent);
  }

  public synchronized void reset() {
    sent.clear();
    failures.clear();
  }
}
