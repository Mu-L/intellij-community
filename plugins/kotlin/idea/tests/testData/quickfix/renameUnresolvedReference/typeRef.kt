// "Rename reference" "false"
// ACTION: Create annotation 'X'
// ACTION: Create class 'X'
// ACTION: Create enum 'X'
// ACTION: Create interface 'X'
// ACTION: Enable a trailing comma by default in the formatter
// ERROR: Unresolved reference: X
// ERROR: Unresolved reference: X
// K2_AFTER_ERROR: Unresolved reference 'X'.
// K2_AFTER_ERROR: Unresolved reference 'X'.
class A {
    class B

    fun foo() {

    }
}

fun test(x: A.<caret>X) {
    val t: A.X
}