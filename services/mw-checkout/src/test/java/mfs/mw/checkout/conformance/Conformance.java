package mfs.mw.checkout.conformance;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Marks a test of the conformance suite (spec 4.3 rule 5, 22.1): the cross-cutting behaviour every
 * service copies from the template (correlation, error envelope, masking, log fields, Kafka
 * retry/DLQ, outbox, inbox). They run with every other test in the one {@code test} task; the tag
 * lets an IDE or {@code --tests} select them. Copy this package unchanged into every service and
 * keep it green.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("conformance")
public @interface Conformance {}
