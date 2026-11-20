package mfs.mw.adapter.remit.messaging;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Consumer-side log context (spec 21.1, 21.3): while a record is handled, the MDC carries its
 * {@code correlationId} and {@code txnId} (headers {@code mw-correlation-id}, {@code mw-txn-id});
 * {@code traceId}/{@code spanId} come from the listener container's Micrometer observation, which
 * continues the record's {@code traceparent}. Both are cleared after every record, so nothing
 * leaks into the next one. The HTTP side does the same in {@code filter/CorrelationIdFilter}.
 *
 * <p>The values are written as they came: the log formatter scrubs any that is not a UUID (18.3).
 */
public final class ConsumerContextInterceptor implements RecordInterceptor<Object, Object> {

  static final String CORRELATION_ID = "correlationId";
  static final String TXN_ID = "txnId";

  @Override
  public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    put(CORRELATION_ID, MwHeaders.text(record.headers().lastHeader(MwHeaders.CORRELATION_ID)));
    put(TXN_ID, MwHeaders.text(record.headers().lastHeader(MwHeaders.TXN_ID)));
    return record;
  }

  @Override
  public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    MDC.remove(CORRELATION_ID);
    MDC.remove(TXN_ID);
  }

  private static void put(String key, String value) {
    if (value == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, value);
    }
  }
}
