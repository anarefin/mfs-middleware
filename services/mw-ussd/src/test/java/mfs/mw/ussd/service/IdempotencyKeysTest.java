package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

  @Test
  void isTheSha256OfChannelMsisdnSessionMenuPathAmountAndTarget() {
    // echo -n 'USSD|01712345678|s-1|main:1>p2p.recipient|50000|01812345678' | shasum -a 256
    assertThat(
            IdempotencyKeys.derive("USSD", "01712345678", "s-1", "main:1>p2p.recipient", "50000", "01812345678"))
        .isEqualTo("562f2723078b68c28bc1ff55783bdccb8cc19c01c0251fedd7088e873457ad4d");
  }

  @Test
  void differsWhenAnyPartDiffers() {
    String base = IdempotencyKeys.derive("USSD", "01712345678", "s-1", "p", "50000", "01812345678");
    assertThat(IdempotencyKeys.derive("USSD", "01712345678", "s-2", "p", "50000", "01812345678")).isNotEqualTo(base);
    assertThat(IdempotencyKeys.derive("USSD", "01712345678", "s-1", "p", "50001", "01812345678")).isNotEqualTo(base);
    assertThat(IdempotencyKeys.derive("USSD", "01712345678", "s-1", "p", "50000", "01812345679")).isNotEqualTo(base);
    assertThat(IdempotencyKeys.derive("USSD", "01712345678", "s-1", "p", "50000", "01812345678")).isEqualTo(base);
  }
}
