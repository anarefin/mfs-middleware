package mfs.mw.app.gateway.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What the device gate ({@link DeviceBindingInterceptor}) asks of an {@code /app/**} handler. A
 * handler without it needs a bound device with a live session ({@link Level#SESSION}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DeviceGate {

  Level value();

  enum Level {
    /** The handler checks the device itself (binding: signed with the key being registered). */
    NONE,
    /** Bound device and a live session. */
    SESSION,
    /** Sensitive operation: bound device, signature, fresh nonce and a live session. */
    SIGNED
  }
}
