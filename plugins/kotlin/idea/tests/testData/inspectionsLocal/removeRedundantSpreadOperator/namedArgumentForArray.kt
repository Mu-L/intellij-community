// ERROR: Assigning single elements to varargs in named form is forbidden
// ERROR: Type mismatch: inferred type is String but Array<out TypeVariable(T)> was expected
// K2_ERROR: Argument type mismatch: actual type is 'String', but 'Array<out uninferred T (of fun <T> arrayOf)>' was expected.
// K2_ERROR: Assigning single elements to varargs in named form is prohibited.

fun foo(vararg x: String) {}

fun bar() {
    foo(<caret>*arrayOf(elements = "abc"))
}
