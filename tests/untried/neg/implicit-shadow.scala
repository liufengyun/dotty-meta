object Test {
    import B._, C.*

    1.isEmpty
}

trait A {
    implicit def i2s(i: Int): String = ""
}

object B extends A

object C extends A
