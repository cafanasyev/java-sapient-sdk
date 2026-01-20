package io.sapient.transport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Method can be used in concurrent environment */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface ThreadSafe {}
