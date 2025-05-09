package org.jetbrains.protocolReader

internal class EnumReader(private val enumClass: Class<Enum<*>>) : ValueReader() {
  override fun appendFinishedValueTypeName(out: TextOutput) {
    out.append(enumClass.canonicalName)
  }

  override fun writeArrayReadCode(scope: ClassScope, subtyping: Boolean, allowSingleValue: Boolean, out: TextOutput) {
    beginReadCall("EnumArray", subtyping, out)
    out.comma().append(enumClass.canonicalName).append("::class.java)")
  }

  override fun writeReadCode(scope: ClassScope, subtyping: Boolean, out: TextOutput) {
    beginReadCall("Enum", subtyping, out)
    out.comma().append(enumClass.canonicalName).append("::class.java)")
  }
}
