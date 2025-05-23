// LANGUAGE_VERSION: 1.4
// DISABLE_ERRORS

annotation class Ann(val x: Int)

@get:Ann(10)<caret>
val a: String
    @Ann(20) get() = "foo"

@set:Ann(10)<caret>
var b: String = ""
    @Ann(20) set(value) { field = value }

@setparam:Ann(10)
var c = " "
    set(@Ann(20) x) {}

@get:Ann(10)
@get:Ann(20)
val d: String
    @Ann(30) @Ann(40) get() = "foo"