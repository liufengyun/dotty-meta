class Foo(n: String @scala.annotation.partial) {
  foo(new Foo("Jack"))

  val name: String = n
  name.length                 // error

  private def foo(o: Foo) = o.name
}