package mfs.mw.simulators.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import mfs.mw.simulators.dto.PartnerStub;
import mfs.mw.simulators.dto.RecordedRequest;
import mfs.mw.simulators.service.PartnerStubStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The generic programmable partner stub (ticket 83) for partner route dry-runs (the dev stack's
 * {@code mw.partners.dry-run.base-url} of the adapters) and the tester console. Control: {@code
 * POST /sim/stubs/<partnerId>} one {@link PartnerStub}, {@code GET} the stubs, {@code DELETE} stubs
 * and requests, {@code GET /sim/stubs/<partnerId>/requests} what came in. Traffic: any method on
 * {@code /sim/partners/<partnerId>/**} gets the matching stub's answer, else 404 {@code {error:
 * NO_STUB, method, path}}.
 */
@RestController
@Validated
public class PartnerStubController {

  static final String PREFIX = "/sim/partners/";
  private static final String ID = "^[a-z0-9][a-z0-9-]{0,63}$";
  private static final Set<String> SKIPPED_HEADERS = Set.of("authorization", "cookie");

  private final PartnerStubStore store;

  public PartnerStubController(PartnerStubStore store) {
    this.store = store;
  }

  @PostMapping("/sim/stubs/{partnerId}")
  PartnerStub put(@PathVariable @Pattern(regexp = ID) String partnerId, @Valid @RequestBody PartnerStub stub) {
    store.put(partnerId, stub);
    return stub;
  }

  @GetMapping("/sim/stubs/{partnerId}")
  List<PartnerStub> stubs(@PathVariable @Pattern(regexp = ID) String partnerId) {
    return store.stubs(partnerId);
  }

  @DeleteMapping("/sim/stubs/{partnerId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void reset(@PathVariable @Pattern(regexp = ID) String partnerId) {
    store.reset(partnerId);
  }

  @GetMapping("/sim/stubs/{partnerId}/requests")
  List<RecordedRequest> requests(@PathVariable @Pattern(regexp = ID) String partnerId) {
    return store.requests(partnerId);
  }

  @RequestMapping("/sim/partners/{partnerId}/**")
  ResponseEntity<Object> serve(
      @PathVariable @Pattern(regexp = ID) String partnerId, HttpServletRequest request,
      @RequestBody(required = false) byte[] body) throws InterruptedException {
    String path = request.getRequestURI().substring((PREFIX + partnerId).length());
    Map<String, String> headers = new LinkedHashMap<>();
    for (String name : Collections.list(request.getHeaderNames())) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (!SKIPPED_HEADERS.contains(lower)) {
        headers.put(lower, request.getHeader(name));
      }
    }
    var stub = store.answer(
        partnerId, request.getMethod(), path, request.getQueryString(), headers,
        body == null ? "" : new String(body, StandardCharsets.UTF_8));
    if (stub.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("error", "NO_STUB", "method", request.getMethod(), "path", path));
    }
    PartnerStub s = stub.get();
    if (s.delayMs() > 0) {
      TimeUnit.MILLISECONDS.sleep(s.delayMs());
    }
    var reply = ResponseEntity.status(s.status());
    s.headers().forEach(reply::header);
    if (s.body() instanceof String text) {
      return reply.body(text);
    }
    return reply.contentType(MediaType.APPLICATION_JSON).body(s.body() == null ? Map.of() : s.body());
  }
}
