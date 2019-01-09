// FILE: 1.kt
package test

public inline fun <T, R> T.mylet(block: (T) -> R): R {
    return block(this)
}


// FILE: 2.kt
import test.*

lateinit var result: UITapGestureRecognizer

open class UITapGestureRecognizer(val fn: () -> CView) {}

abstract class CView {
    var cView: String? = null
        set(value) {
            value?.mylet { v ->
                result = object : UITapGestureRecognizer({ this }) {}
            }
            field = value
        }
}

fun box(): String {
    object : CView() {}.cView = "OK"
    return result.fn().cView!!
}