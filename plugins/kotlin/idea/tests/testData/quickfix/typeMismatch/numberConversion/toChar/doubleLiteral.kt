// "Convert expression to 'Char'" "true"
fun char(x: Char) {}

fun test() {
    char(<caret>1.0)
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.NumberConversionFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.NumberConversionFix