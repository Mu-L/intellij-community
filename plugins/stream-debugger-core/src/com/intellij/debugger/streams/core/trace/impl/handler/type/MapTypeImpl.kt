// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.core.trace.impl.handler.type

/**
 * @author Vitaliy.Bibaev
 */
class MapTypeImpl(override val keyType: GenericType,
                  override val valueType: GenericType,
                  toName: (String, String) -> String,
                  defaultValue: String,
                  toEntryType: (String, String) -> String,
                  )
  : ClassTypeImpl(toName.invoke(keyType.genericTypeName, valueType.genericTypeName), defaultValue), MapType
{
  override val entryType = ClassTypeImpl(toEntryType.invoke(keyType.genericTypeName, valueType.genericTypeName))
}
