// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.compose.ide.plugin.shared

inline fun <T, K, V> Iterable<T>.associateNotNull(transform: (T) -> Pair<K, V>?): Map<K, V> =
  buildMap {
    for (item in this@associateNotNull) {
      val (key, value) = transform(item) ?: continue
      this[key] = value
    }
  }