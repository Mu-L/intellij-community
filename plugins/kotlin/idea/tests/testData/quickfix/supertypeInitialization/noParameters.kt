// "class org.jetbrains.kotlin.idea.quickfix.SuperClassNotInitialized$AddParametersFix" "false"
// ACTION: Change to constructor invocation
// ERROR: This type has a constructor, and thus must be initialized here
// K2_AFTER_ERROR: This type has a constructor, so it must be initialized here.
open class Base

class C : Base<caret>
