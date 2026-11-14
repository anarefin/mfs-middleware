package mfs.mw.crypto.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import mfs.mw.crypto.exception.MwException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BytesTest {

  /** RFC 4648 section 10 test vectors, padding dropped. */
  @ParameterizedTest
  @CsvSource({"f, MY", "fo, MZXQ", "foo, MZXW6", "foob, MZXW6YQ", "fooba, MZXW6YTB", "foobar, MZXW6YTBOI"})
  void base32IsRfc4648WithoutPadding(String in, String out) {
    assertThat(Bytes.base32(in.getBytes(StandardCharsets.US_ASCII))).isEqualTo(out);
  }

  @Test
  void badBase64IsAFieldError() {
    assertThatThrownBy(() -> Bytes.decode("ciphertext", "abcde"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-VAL-001");
          assertThat(e.details()).singleElement().satisfies(d -> assertThat(d.field()).isEqualTo("ciphertext"));
        });
  }

  @Test
  void zeroOverwritesEveryBuffer() {
    byte[] a = {1, 2};
    byte[] b = {3};
    Bytes.zero(a, null, b);
    assertThat(a).containsOnly(0);
    assertThat(b).containsOnly(0);
  }
}
