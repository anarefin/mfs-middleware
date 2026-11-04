package mfs.mw.core.mock.controller;

import jakarta.validation.Valid;
import java.util.List;
import mfs.mw.core.mock.dto.Fault;
import mfs.mw.core.mock.dto.FaultRequest;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import mfs.mw.core.mock.service.FaultRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test/admin API of the mock (not a product contract): inject faults per command type or txnId so
 * UNKNOWN and TSQ paths can be exercised. {@code DELETE /faults} is the reset call.
 */
@RestController
@RequestMapping("/core-mock/v1/faults")
public class FaultController {

  private final FaultRegistry registry;

  public FaultController(FaultRegistry registry) {
    this.registry = registry;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  Fault add(@Valid @RequestBody FaultRequest request) {
    return registry.add(request);
  }

  @GetMapping
  List<Fault> list() {
    return registry.list();
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void reset() {
    registry.reset();
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(@PathVariable String id) {
    if (!registry.remove(id)) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "fault not found");
    }
  }
}
