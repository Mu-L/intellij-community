// COMPILER_ARGUMENTS: -XXLanguage:+EnumEntries -opt-in=kotlin.ExperimentalStdlibApi
// WITH_STDLIB
enum class EnumClass

fun test() {
    val res: Array<EnumClass> = run { EnumClass.<caret>values() })
}