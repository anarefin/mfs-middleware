package mfs.mw.app.gateway.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Pattern;
import mfs.mw.app.gateway.util.Msisdn;

/**
 * {@code creditParty.id} in the format its {@code type} needs: a bill number for {@code
 * BILLER_ACCOUNT}, an accepted MSISDN otherwise (type absent, {@code WALLET}, {@code MSISDN}). The
 * violation is reported on {@code id}, so the error detail names {@code creditParty.id}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CounterPartyId.Validator.class)
public @interface CounterPartyId {

  String message() default "must be a mobile number, or a bill number for BILLER_ACCOUNT";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  /** Checks the id against the pattern of its type. */
  class Validator implements ConstraintValidator<CounterPartyId, TransactionRequest.CounterParty> {

    private static final Pattern BILL = Pattern.compile(TransactionRequest.BILL_NUMBER);

    @Override
    public boolean isValid(TransactionRequest.CounterParty party, ConstraintValidatorContext ctx) {
      if (party == null || party.id() == null || party.id().isBlank()) {
        return true; // @NotBlank reports it
      }
      boolean ok =
          "BILLER_ACCOUNT".equals(party.type())
              ? BILL.matcher(party.id()).matches()
              : Msisdn.canonical(party.id()) != null;
      if (!ok) {
        ctx.disableDefaultConstraintViolation();
        ctx.buildConstraintViolationWithTemplate(ctx.getDefaultConstraintMessageTemplate())
            .addPropertyNode("id")
            .addConstraintViolation();
      }
      return ok;
    }
  }
}
