// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.extensions;

import com.intellij.util.xmlb.annotations.Attribute;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a property as required in an Extension Point bean declaration.
 * <p/>
 * An empty value ({@code attributeName=""}) can be allowed explicitly via {@link #allowEmpty()}.
 *
 * @see Attribute
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD})
public @interface RequiredElement {

  /**
   * @return {@code true} if the specified property value can be empty.
   */
  boolean allowEmpty() default false;
}
