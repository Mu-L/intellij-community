// WITH_STDLIB
// COMPILER_ARGUMENTS: -XXLanguage:+BreakContinueInInlineLambdas
// DISABLE-ERRORS
fun foo() {
    for (i in 1..5) {
        (1..5).forEach({
            if (it == 2) co<caret>ntinue
        })
    }
}