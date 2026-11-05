package mfs.mw.admin.api.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Code namespace to HTTP status and retryable flag (spec 5.3). */
class ErrorCategoryTest {

  @ParameterizedTest
  @CsvSource({
    "MW-VAL-001,               VAL,  400, false",
    "MW-AUTH-001,              AUTH, 401, false",
    "MW-IDEM-001,              IDEM, 409, false",
    "MW-CORE-INSUFFICIENT-BAL, CORE, 422, false",
    "MW-PTR-DESCO-17,          PTR,  502, false",
    "MW-TMO-001,               TMO,  504, true",
    "MW-SYS-001,               SYS,  500, true",
  })
  void namespaceDecidesStatusAndRetryable(
      String code, ErrorCategory category, int status, boolean retryable) {
    assertThat(ErrorCategory.of(code)).isEqualTo(category);
    assertThat(category.status().value()).isEqualTo(status);
    assertThat(category.retryable()).isEqualTo(retryable);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "VAL-001", "MW-FOO-1", "mw-val-001", "MW-VAL"})
  void unknownNamespaceIsRejected(String code) {
    assertThatThrownBy(() -> ErrorCategory.of(code)).isInstanceOf(IllegalArgumentException.class);
  }
}
