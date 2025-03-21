// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.debugger.sequence.trace.dsl

import com.intellij.debugger.streams.core.trace.dsl.Types
import com.intellij.debugger.streams.core.trace.impl.handler.type.*
import org.jetbrains.kotlin.builtins.StandardNames.FqNames

object KotlinSequenceTypes : Types {
    override val ANY: GenericType = ClassTypeImpl(FqNames.any.asString(), "kotlin.Any()")

    override val BOOLEAN: GenericType = ClassTypeImpl(FqNames._boolean.asString(), "false")
    val BYTE: GenericType = ClassTypeImpl(FqNames._byte.asString(), "0")
    val SHORT: GenericType = ClassTypeImpl(FqNames._short.asString(), "0")
    val CHAR: GenericType = ClassTypeImpl(FqNames._char.asString(), "0.toChar()")
    override val INT: GenericType = ClassTypeImpl(FqNames._int.asString(), "0")
    override val LONG: GenericType = ClassTypeImpl(FqNames._long.asString(), "0L")
    val FLOAT: GenericType = ClassTypeImpl(FqNames._float.asString(), "0.0f")
    override val DOUBLE: GenericType = ClassTypeImpl(FqNames._double.asString(), "0.0")
    override val STRING: GenericType = ClassTypeImpl(FqNames.string.asString(), "\"\"")
    override val EXCEPTION: GenericType = ClassTypeImpl(FqNames.throwable.asString(), "kotlin.Throwable()")
    override val VOID: GenericType = ClassTypeImpl(FqNames.unit.asString(), "Unit")

    val NULLABLE_ANY: GenericType = nullable { ANY }

    override val TIME: GenericType = ClassTypeImpl(
        "java.util.concurrent.atomic.AtomicInteger",
        "java.util.concurrent.atomic.AtomicInteger()"
    )

    override fun list(elementsType: GenericType): ListType =
        ListTypeImpl(elementsType, { "kotlin.collections.MutableList<$it>" }, "kotlin.collections.mutableListOf()")

    override fun array(elementType: GenericType): ArrayType = when (elementType) {
        BOOLEAN -> ArrayTypeImpl(BOOLEAN, { "kotlin.BooleanArray" }, { "kotlin.BooleanArray($it)" })
        BYTE -> ArrayTypeImpl(BYTE, { "kotlin.ByteArray" }, { "kotlin.ByteArray($it)" })
        SHORT -> ArrayTypeImpl(SHORT, { "kotlin.ShortArray" }, { "kotlin.ShortArray($it)" })
        CHAR -> ArrayTypeImpl(CHAR, { "kotlin.CharArray" }, { "kotlin.CharArray($it)" })
        INT -> ArrayTypeImpl(INT, { "kotlin.IntArray" }, { "kotlin.IntArray($it)" })
        LONG -> ArrayTypeImpl(LONG, { "kotlin.LongArray" }, { "kotlin.LongArray($it)" })
        FLOAT -> ArrayTypeImpl(FLOAT, { "kotlin.FloatArray" }, { "kotlin.FloatArray($it)" })
        DOUBLE -> ArrayTypeImpl(DOUBLE, { "kotlin.DoubleArray" }, { "kotlin.DoubleArray($it)" })
        else -> ArrayTypeImpl(nullable { elementType }, { "kotlin.Array<$it>" },
                              { "kotlin.arrayOfNulls<${elementType.genericTypeName}>($it)" })
    }

    private fun mapEntryType(keys: String, values: String) : String = "kotlin.collections.Map.Entry<$keys, $values>"

    override fun map(keyType: GenericType, valueType: GenericType): MapType =
        MapTypeImpl(
            keyType, valueType,
            { keys, values -> "kotlin.collections.MutableMap<$keys, $values>" },
            "kotlin.collections.mutableMapOf()",
            ::mapEntryType
        )

    override fun linkedMap(keyType: GenericType, valueType: GenericType): MapType =
        MapTypeImpl(
            keyType, valueType,
            { keys, values -> "kotlin.collections.MutableMap<$keys, $values>" },
            "kotlin.collections.linkedMapOf()",
            ::mapEntryType
        )

    override fun nullable(typeSelector: Types.() -> GenericType): GenericType {
        val type = this.typeSelector()
        if (type.genericTypeName.last() == '?') return type
        return when (type) {
            is ArrayType -> ArrayTypeImpl(type.elementType, { "kotlin.Array<$it>?" }, { type.sizedDeclaration(it) })
            is ListType -> ListTypeImpl(type.elementType, { "kotlin.collections.MutableList<$it>?" }, type.defaultValue)
            is MapType -> MapTypeImpl(
                type.keyType, type.valueType, { keys, values -> "kotlin.collections.MutableMap<$keys, $values>?" },
                type.defaultValue,
                ::mapEntryType
            )
            else -> ClassTypeImpl(type.genericTypeName + '?', type.defaultValue)
        }
    }

    private val primitiveTypesIndex: Map<String, GenericType> =
        listOf(
            BOOLEAN, BYTE, INT, SHORT,
            CHAR, LONG, FLOAT, DOUBLE
        ).associateBy { it.genericTypeName }

    private val primitiveArraysIndex: Map<String, ArrayType> = primitiveTypesIndex.asSequence()
        .map { array(it.value) }
        .associateBy { it.genericTypeName }

    fun primitiveTypeByName(typeName: String): GenericType? = primitiveTypesIndex[typeName]

    fun primitiveArrayByName(typeName: String): ArrayType? = primitiveArraysIndex[typeName]
}

