/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Public contract preserved across the 0.3 beta series unless explicitly migrated. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR,
        ElementType.FIELD, ElementType.PACKAGE})
public @interface Stable {
}
