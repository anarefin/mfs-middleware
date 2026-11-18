package mfs.mw.notify.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * Strict UTF-8 key and value deserializer: bytes that are not valid UTF-8 throw instead of being
 * decoded with replacement characters, as Kafka's {@code StringDeserializer} does. Every key and
 * JSON body on the platform is UTF-8 text, so such a record is a poison record. Spring Kafka's
 * {@code ErrorHandlingDeserializer} wraps this one ({@code application.yml}): the failure becomes a
 * {@code DeserializationException}, not retried, and the record goes straight to the DLQ without
 * blocking its partition (ADR D25).
 */
public final class Utf8Deserializer implements Deserializer<String> {

  @Override
  public String deserialize(String topic, byte[] data) {
    if (data == null) {
      return null;
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data))
          .toString();
    } catch (CharacterCodingException e) {
      throw new SerializationException("record on " + topic + " is not valid UTF-8", e);
    }
  }
}
