// FIX: Replace 'if' expression with safe access expression
// WITH_STDLIB
// HIGHLIGHT: INFORMATION
fun convert(x: String, y: String) = ""

fun foo(a: Any?, b: String) {
    <caret>if (a is String) convert(a, b) else null
}