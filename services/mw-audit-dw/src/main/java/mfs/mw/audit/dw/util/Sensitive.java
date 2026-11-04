package mfs.mw.audit.dw.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a DTO record component (or field) that holds personal or secret data. {@link
 * MaskingPolicy#describe(Object)} masks it, and the JSON log formatter applies that to every
 * log argument, so a marked value is never written raw (spec 18.3, SEC-10).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface Sensitive {
  SensitiveType value();
}
