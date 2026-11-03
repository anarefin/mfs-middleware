# make verify / check-field-injection: no @Autowired fields under any module's src/
# (constructor injection only, main and test code alike).

java_file() { mkdir -p "$(dirname "$1")"; cat >"$1"; }

test_field_injection_check_passes_on_constructor_and_parameter_injection() {
  new_module "$PWD" services/mw-a
  java_file services/mw-a/src/test/java/a/OkTest.java <<'JAVA'
class OkTest {
  private final Foo foo;
  @Autowired
  OkTest(Foo foo) { this.foo = foo; }
  @Autowired OkTest(Foo foo, Bar bar) { this.foo = foo; }
  @BeforeAll static void setUp(@Autowired JdbcTemplate jdbc) {}
  // @Autowired Foo commentedOut;
}
JAVA
  assert_ok mw_make "$PWD" check-field-injection
}

test_field_injection_check_fails_on_same_line_field() {
  new_module "$PWD" services/mw-a
  java_file services/mw-a/src/test/java/a/BadIT.java <<'JAVA'
class BadIT {
  @Autowired JdbcTemplate jdbc;
}
JAVA
  assert_fails mw_make "$PWD" check-field-injection
  assert_out_contains "BadIT.java:2"
}

test_field_injection_check_fails_on_field_below_the_annotation() {
  new_module "$PWD" services/mw-a
  java_file services/mw-a/src/main/java/a/Bad.java <<'JAVA'
class Bad {
  @Autowired
  @Qualifier("x")
  private Map<String, List<Foo>> foos;
}
JAVA
  assert_fails mw_make "$PWD" check-field-injection
  assert_out_contains "Bad.java:2"
}

test_field_injection_check_fails_on_kotlin_style_lateinit() {
  new_module "$PWD" services/mw-a
  java_file services/mw-a/src/test/kotlin/a/BadTest.kt <<'KT'
class BadTest {
  @Autowired lateinit var foo: Foo
}
KT
  assert_fails mw_make "$PWD" check-field-injection
}

test_verify_fails_on_field_injection_before_gradle() {
  new_module "$PWD" services/mw-a
  java_file services/mw-a/src/test/java/a/BadIT.java <<'JAVA'
class BadIT { @Autowired private Foo foo; }
JAVA
  assert_fails mw_make "$PWD" verify
  assert_out_contains "FIELD-INJECTION"
  [ ! -f services/mw-a/.gradlew-calls ] || fail "gradle ran despite field injection"
}

test_real_repo_has_no_field_injection() {
  OUT="$(make -s -f "$REPO_DIR/Makefile" SELFTEST=0 check-field-injection 2>&1)" || fail "field injection in the repo"
}
