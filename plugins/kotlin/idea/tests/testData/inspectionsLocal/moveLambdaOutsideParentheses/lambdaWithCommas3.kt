// IS_APPLICABLE: true
fun bar(a: Int, b: Int, f: () -> Unit) {}
fun foo(a: Int, b: Int) = 2

fun test() {
    bar(1, 2, /* , , */ {<caret>
        val a = foo(1, 2)
    })
}