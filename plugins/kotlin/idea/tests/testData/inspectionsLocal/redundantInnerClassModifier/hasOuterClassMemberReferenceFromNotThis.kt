class Test {
    <caret>inner class Inner {
        fun test() {
            Test().innerVal
            Test().innerFun()
            Test().Inner2()
            Test().Inner2().inner2Fun()
        }
    }

    val innerVal = 1

    fun innerFun() {}

    inner class Inner2 {
        fun inner2Fun() {}
    }
}
