package dotty.tools.dotc
package transform
package init

import core._
import Contexts.Context
import StdNames._
import Names._
import ast._
import Trees._
import Symbols._
import Types._
import Decorators._
import util.Positions._
import config.Printers.init.{ println => debug }
import collection.mutable

//=======================================
//           Heap / Env
//=======================================

trait HeapEntry extends Cloneable {
  val id: Int = Heap.uniqueId
  var heap: Heap = null

  override def clone: HeapEntry = super.clone.asInstanceOf[HeapEntry]
}

object Heap {
  private var _uniqueId = 0
  def uniqueId: Int = {
    _uniqueId += 1
    _uniqueId
  }

  class RootEnv extends Env(-1) {
    override def contains(sym: Symbol): Boolean = _syms.contains(sym)

    override def containsClass(sym: Symbol): Boolean = _symtab.contains(sym)
  }

  def createRootEnv: Env = {
    val heap = new Heap
    val env = new RootEnv
    heap.add(env)
    env
  }

  def join(entry1: HeapEntry, entry2: HeapEntry): HeapEntry = (entry1, entry2) match {
    case (env1: Env, env2: Env) =>
      env1.join(env2)
    case (obj1: ObjectRep, obj2: ObjectRep) =>
      obj1.join(obj2)
    case _ =>
      throw new Exception(s"Cannot join $entry1 and $entry2")
  }
}

class Heap extends Cloneable {
  private var _parent: Heap = null
  protected var _entries: mutable.Map[Int, HeapEntry] = mutable.Map()

  def apply(id: Int) =_entries(id)

  def contains(id: Int) = _entries.contains(id)

  def add(entry: HeapEntry) = {
    entry.heap = this
    _entries(entry.id) = entry
  }

  override def clone: Heap = {
    val heap = new Heap
    heap._parent = this
    heap._entries = mutable.Map()

    this._entries.foreach { case (id, entry) =>
      val entry2: HeapEntry = entry.clone
      entry2.heap = heap
      heap._entries(id) = entry2
    }

    heap
  }

  def join(heap2: Heap)(implicit ctx: Context): Heap = {
    assert(heap2._parent `eq` this)
    heap2._entries.foreach { case (id: Int, entry: HeapEntry) =>
      if (this.contains(id))
        this._entries(id) = Heap.join(this(id), entry)
      else {
        entry.heap = this
        this._entries(id) = entry
      }
    }
    this
  }
}

//=======================================
//             values
//=======================================

sealed trait Scope {
  /** Select a member on a value */
  def select(sym: Symbol, heap: Heap, pos: Position): Res

  /** Assign on a value */
  def assign(sym: Symbol, value: Value, heap: Heap, pos: Position): Res

  /** Index an inner class with current value as the immediate outer */
  def index(cls: ClassSymbol, tp: Type, obj: ObjectRep): Set[ObjectRep]
}

/** Abstract values in analysis */
sealed trait Value extends Scope {
  /** Apply a method or function to the provided arguments */
  def apply(values: Int => Value, paramTps: List[Type], argPos: List[Position], pos: Position, heap: Heap): Res

  def join(other: Value): Value = (this, other) match {
    case (v1: OpaqueValue, v2: OpaqueValue)     =>
      OpaqueValue(Math.min(state, other.state))
    case (m1: FunctionValue, m2: FunctionValue) =>
      UnionValue(Set(m1, m2))
    case (o1: ObjectValue, o2: ObjectValue) =>
      if (o1.id == o2.id) o1
      else new UnionValue(Set(o1, o2))
    case (v1: UnionValue, v2: UnionValue) =>
      v1 ++ v2
    case (uv: UnionValue, v: Value) =>
      uv + v
    case (v: Value, uv: UnionValue) =>
      uv + v
    case (v1, v2) =>
      UnionValue(Set(v1, v2))
    case _ =>
      throw new Exception(s"Can't join $this and $other")
  }

  /** Widen the value to an opaque value
   *
   *  Widening is needed at analysis boundary.
   */
  def widen(heap: Heap, pos: Position): OpaqueValue = {
    val testHeap = heap.clone
    def recur(value: Value): OpaqueValue = value match {
      case ov: OpaqueValue => ov
      case fv: FunctionValue =>
        val res = fv(FullValue, testHeap)
        if (res.hasErrors) FilledValue
        else recur(res.value)
      case obj: ObjectValue =>
        FilledValue
      case UnionValue(vs) =>
        vs.foldLeft(FullValue) { (v, acc) =>
          if (v == PartialValue) return PartialValue
          else recur(v).join(acc)
        }
    }

    recur(this)
  }
}

trait SingleValue extends Value

/** Union of values */
case class UnionValue(val values: Set[SingleValue]) extends Value {
  def apply(values: Int => Value, paramTps: List[Type], argPos: List[Position], pos: Position, heap: Heap): Res = {
    values.foldLeft(Res()) { (acc, value) =>
      value.apply(values, paramTps, argPos, pos heap).join(acc)
    }
  }

  def select(sym: Symbol, heap: Heap, pos: Position): Res = {
    values.foldLeft(Res()) { (acc, value) =>
      value.select(sym, heap, pos).join(acc)
    }
  }

  def assign(sym: Symbol, value: Value, heap: Heap, pos: Position): Res = {
    values.foldLeft(Res()) { (acc, value) =>
      value.assign(sym, value, heap, pos).join(acc)
    }
  }

  def index(cls: ClassSymbol, tp: Type, obj: ObjectRep): Set[ObjectRep] = {
    var used = false
    values.flatMap { value =>
      val obj2 = if (used) obj.fresh else { used = true; obj }
      value.index(cls, tp, obj2)
    }
  }

  def +(value: Value): UnionValue = UnionValue(values + value)
  def ++(uv: UnionValue): UnionValue = UnionValue(values + uv.values)
}

/** Values that are subject to type checking rather than analysis */
sealed class OpaqueValue extends SingleValue {
  def apply(values: Int => Value, paramTps: List[Type], argPos: List[Position], pos: Position, heap: Heap): Res = {
    assert(this == FullValue)

    def paramValue(index: Int) = Analyzer.typeValue(paramTps(index))

    values.zipWithIndex.foreach { case (value, index) =>
      val pos = argPos(index)
      if (value.widen(heap, pos) < paramValue(index))
        return Res(effects = Vector(Generic("Leak of object under initialization", pos)))
    }

    Res()
  }

  def index(cls: ClassSymbol, tp: Type, obj: ObjectRep): Set[ObjectRep] = Set(obj)

  def <(that: OpaqueValue): Boolean = (this, that) match {
    case (FullValue, _) => false
    case (FilledValue, PartialValue | FilledValue) => false
    case (PartialValue, PartialValue) => false
    case _ => true
  }

  def join(that: OpaqueValue): OpaqueValue =
    if (this < that) this else that

  def meet(that: OpaqueValue): OpaqueValue =
    if (this < that) that else this
}

/** Values that are subject to analysis rather than type checking */
sealed trait TransparentValue extends SingleValue

object FullValue extends OpaqueValue {
  def select(sym: Symbol, heap: Heap, pos: Position): Res = Res()

  def assign(sym: Symbol, value: Value, heap: Heap, pos: Position): Res =
    if (!value.widen(heap, pos).isFull)
      Res(effects = Vector(Generic("Cannot assign an object under initialization to a full object", pos)))
    else Res()
}

object PartialValue extends OpaqueValue {
  def select(sym: Symbol, heap: Heap, pos: Position): Res = {
    val res = Res()

    if (sym.is(Method)) {
      if (!Analyzer.isPartial(sym))
        res += Generic(s"The $sym should be marked as `@partial` in order to be called", pos)
    }
    else if (sym.is(Lazy)) {
      if (!Analyzer.isPartial(sym))
        res += Generic(s"The lazy field $sym should be marked as `@partial` in order to be accessed", pos)
    }
    else if (sym.isClass) {
      if (!Analyzer.isPartial(sym))
        res += Generic(s"The nested $sym should be marked as `@partial` in order to be instantiated", pos)
    }
    else {  // field select
      if (!Analyzer.isPrimaryConstructorFields(sym) && !sym.owner.is(Trait))
        res += Generic(s"Cannot access field $sym on a partial object", pos)
    }

    // set state to Full, don't report same error message again
    Res(value = FullValue, effects = res.effects)
  }

  /** assign to partial is always fine? */
  def assign(sym: Symbol, value: Value, heap: Heap, pos: Position): Res = Res()
}

object FilledValue extends OpaqueValue {
  def select(sym: Symbol, heap: Heap, pos: Position): Res =
    if (sym.is(Method)) {
      if (!Analyzer.isPartial(sym) && !Analyzer.isFilled(sym))
        res += Generic(s"The $sym should be marked as `@partial` or `@filled` in order to be called", pos)

      Res(value = FullValue, effects = res.effects)
    }
    else if (sym.is(Lazy)) {
      if (!Analyzer.isPartial(sym) && !Analyzer.isFilled(sym))
        res += Generic(s"The lazy field $sym should be marked as `@partial` or `@filled` in order to be accessed", pos)

      Res(value = Analyzer.typeValue(sym.info), effects = res.effects)
    }
    else if (sym.isClass) {
      if (!Analyzer.isPartial(sym) && !Analyzer.isFilled(sym))
        res += Generic(s"The nested $sym should be marked as `@partial` or `@filled` in order to be instantiated", pos)

      Res(effects = res.effects)
    }
    else {
      Res(value = Analyzer.typeValue(sym.info), effects = res.effects)
    }

  def assign(sym: Symbol, value: Value, heap: Heap, pos: Position): Res =
    if (value.widen(heap, pos) < Analyzer.typeValue(sym.info))
      Res(effects = Vector(Generic("Cannot assign an object of a lower state to a field of higher state", pos)))
    Res()
}


/** A function value or value of method select */
case class FunctionValue(fun: (Int => Value, List[Position], Position, Heap) => Res) extends TransparentValue {
  def apply(values: Int => Value, paramTps: List[Type], argPos: List[Position], pos: Position, heap: Heap): Res =
    fun(values, argPos, pos, heap)

  def select(sym: Symbol, heap: Heap, pos: Position): Res = {
    assert(sym.name.toString == "apply")
    Res(value = this)
  }

  /** not supported */
  def assign(sym: Symbol, value: Value, heap: Heap, pos: Position): Res = ???
  def index(cls: ClassSymbol, tp: Type, obj: ObjectRep): Set[ObjectRep] = ???
}

/** An object value */
class ObjectValue(val id: Int)(implicit ctx: Context) extends TransparentValue {
  /** not supported, impossible to apply an object value */
  def apply(values: Int => Value, paramTps: List[Type], argPos: List[Position], pos: Position, heap: Heap): Res = ???

  // handle dynamic dispatch
  private def resolve(sym: Symbol, tp: Type, open: Boolean): Symbol = {
    if (sym.isClass || sym.isConstructor || sym.isEffectivelyFinal || sym.is(Private)) sym
    else sym.matchingMember(tp)
  }

  def select(sym: Symbol, heap: Heap, pos: Position, env: Env): Res = {
    val obj = heap(id).asInstanceOf[ObjectRep]
    val target = resolve(sym, obj.tp, obj.open)
    if (env.contains(target)) {
      val info = obj(target)
      if (target.is(Lazy)) {
        if (!info.forced) {
          val res = info.value(Nil, obj.heap)
          obj(target) = info.copy(forced = true, value = res.value)

          if (res.hasErrors) Res(effects = Vector(Force(sym, res.effects, pos)))
          else Res(value = res.value)
        }
        else Res(value = info.value)
      }
      else if (target.is(Method)) {
        val res =
          if (target.info.isInstanceOf[ExprType]) {       // parameter-less call
            val res2 = info.value(Nil, env.heap)

            if (res2.effects.nonEmpty)
              res2.effects = Vector(Call(target, res2.effects, pos))

            res2
          }
          else Res(value = info.Value)

        if (obj.open && !target.hasAnnotation(defn.PartialAnnot) && !sym.isEffectivelyFinal)
          res += OverrideRisk(sym, pos)

        res
      }
      else {
        var effs = Vector.empty[Effect]
        if (!info.assigned) effs = effs :+ Uninit(target, pos)

        val res = Res(effects = effs, value = info.value)

        if (target.is(Deferred) && !target.hasAnnotation(defn.InitAnnot))
          res += UseAbstractDef(target, pos)

        res
      }
    }
    else if (this.classInfos.contains(target)) Res()
    else {
      // two cases: (1) select on unknown super; (2) select on self annotation
      if (obj.tp.classSymbol.isSubClass(target.owner)) FilledValue.select(target, heap, pos, env)
      else PartialValue.select(target, heap, pos, env)
    }
  }

  def assign(sym: Symbol, value: Value, heap: Heap, pos: Position): Res = {
    val obj = heap(id).asInstanceOf[ObjectRep]
    val target = resolve(sym, obj.tp, obj.open)
    if (obj.contains(target)) {
      obj(target) = SymInfo(assigned = true, value = value)
      Res()
    }
    else {
      // two cases: (1) select on unknown super; (2) select on self annotation
      if (obj.tp.classSymbol.isSubClass(target.owner)) FilledValue.assign(target, value, heap, pos)
      else PartialValue.assign(target, value, heap, pos)
    }
  }

  def index(cls: ClassSymbol, tp: Type, obj: ObjectRep): Set[ObjectRep] = {
    val outer = obj.heap(id).asInstanceOf[ObjectRep]
    if (outer.classInfos.contains(cls)) {
      val (tmpl, envId) = outer.classInfos(cls)
      val envOuter = outer.heap(envId)
      Indexing.indexClass(cls, tmpl, obj, envOuter)
    }
    else {
      val value = Indexing.unknownConstructorValue(cls)
      obj.add(cls.primaryConstructor, SymInfo(value = value))
    }

    Set(obj)
  }

  override def hashCode = id

  override def equals(that: Any) = that match {
    case that: AtomObjectValue => that.id == id
    case _ => false
  }
}

//=======================================
//           environment
//=======================================

/** Information about fields or local variables
 *
 *  TODO: possibility to remove this concept by introducing `NoValue`
 */
case class SymInfo(assigned: Boolean = true, forced: Boolean = false, value: Value)

/** The state of closure and objects
  *
  *  @param outerId required for modelling closures
  *
  *  Invariants:
  *  1. the data stored in the immutable map must be immutable
  *  2. environment refer each other via `id`, which implies TransparentValue should
  *     never use captured environment other than its `id`.
  */
class Env(outerId: Int) extends HeapEntry with Scope {
  assert(outerId != id)

  /** local symbols defined in current scope */
  protected var _syms: Map[Symbol, SymInfo] = Map()

  /** local class definitions */
  private var _classInfos: Map[ClassSymbol, (Template, Int)] = List()
  def add(cls: ClassSymbol, info: (Template, Int)) = _classInfos(cls) = info
  def classInfos: Map[ClassSymbol, (Template, Int)] = _classInfos

  def outer: Env = heap(outerId).asInstanceOf[Env]

  def deepClone: Env = {
    val heap2 = heap.clone
    heap2(this.id).asInstanceOf[Env]
  }

  def fresh(heap: Heap = this.heap): Env = {
    val env = new Env(this.id)
    heap.add(env)
    env
  }

  def newObject(tp: Type, heap: Heap = this.heap, open: Boolean = true): ObjectRep = {
    val obj = new ObjectRep(tp, open)
    heap.add(obj)
    obj
  }

  def apply(sym: Symbol): SymInfo =
    if (_syms.contains(sym)) _syms(sym)
    else outer(sym)

  def add(sym: Symbol, info: SymInfo) =
    _syms = _syms.updated(sym, info)

  def update(sym: Symbol, info: SymInfo): Unit =
    if (_syms.contains(sym)) _syms = _syms.updated(sym, info)
    else outer.update(sym, info)

  def contains(sym: Symbol): Boolean = _syms.contains(sym) || outer.contains(sym)

  def notAssigned = _syms.keys.filter(sym => !(_syms(sym).assigned))
  def forcedSyms  = _syms.keys.filter(sym => _syms(sym).forced)

  def join(env2: Env): Env = {
    assert(this.id == env2.id)

    _syms.foreach { case (sym: Symbol, info: SymInfo) =>
      assert(env2.contains(sym))
      val info2 = env2._syms(sym)
      // path-insensitive approximation:
      // 1. if a variable is assigned in one branch, but not in the other branch, we
      //    treat the variable as not assigned.
      // 2. a lazy variable is force if it's forced in either branch
      // 3. a variable gets the lowest possible state
      if (!info.assigned || !info2.assigned)
        _syms = _syms.updated(sym, info.copy(assigned = false, transparentValue = NoTransparent))
      else {
        val infoRes = info.copy(
          assigned   =  true,
          forced     =  info.forced || info2.forced,
          value      =  info.value.join(info2.value)
        )
        _syms = _syms.updated(sym, infoRes)
      }
    }

    this
  }

  /** Assign to a local variable, i.e. TermRef with NoPrefix */
  def assign(sym: Symbol, value: Value, pos: Position)(implicit ctx: Context): Res =
    if (this.contains(sym)) {
      this(sym) = SymInfo(assigned = true, value = value)
      Res()
    }
    else if (value.widen != FullValue) // leak assign
      Res(effects = Vector(Generic("Cannot leak an object under initialization", pos)))
    else Res()


  /** Select a local variable, i.e. TermRef with NoPrefix */
  def select(sym: Symbol, pos: Position)(implicit ctx: Context): Res =
    if (this.contains(sym)) {
      val symInfo = this(sym)
      if (sym.is(Lazy)) {
        if (!symInfo.forced) {
          val res = symInfo.value(i => FullValue, this.heap)
          this(sym) = symInfo.copy(forced = true, value = res.value)

          if (res.hasErrors) Res(effects = Vector(Force(sym, res.effects, pos)))
          else Res(value = res.value)
        }
        else Res(value = symInfo.value)
      }
      else if (sym.is(Method)) {
        if (sym.info.isInstanceOf[ExprType]) {       // parameter-less call
          val res2 = symInfo.value(i => FullValue, this.heap)

          if (res2.effects.nonEmpty)
            res2.effects = Vector(Call(sym, res2.effects, pos))

          res2
        }
        else Res(value = symInfo.value)
      }
      else {
        var effs = Vector.empty[Effect]
        if (!symInfo.assigned) effs = effs :+ Uninit(sym, pos)

        Res(effects = effs, value = symInfo.value)
      }
    }
    else if (this.classInfos.contains(sym)) Res()
    else {
      // How do we know the class/method/field does not capture/use a partial/filled outer?
      // If method/field exist, then the outer class beyond the method/field is full,
      // i.e. external methods/fields/classes are always safe.
      Res()
    }

  def index(cls: ClassSymbol, tp: Type, obj: ObjectRep): Set[ObjectRep] = {
    if (this.classInfos.contains(cls)) {
      val tmpl = this.classInfos(cls)
      Indexing.indexClass(cls, tmpl, obj, this)
    }
    else {
      val value = Indexing.unknownConstructorValue(cls)
      obj.add(cls.primaryConstructor, SymInfo(value = value))
    }

    Set(obj)
  }

  override def toString: String =
    (if (outerId > 0) outer.toString + "\n" else "") ++
    s"""~ --------------${getClass} - $id($outerId)-----------------------
        ~ | locals:  ${_syms.keys}
        ~ | not initialized:  ${notAssigned}
        ~ | lazy forced:  ${forcedSyms}"""
    .stripMargin('~')
}

/** A container holds all information about fields of an object and outers of nested classes
 */
class ObjectRep(val tp: Type, val open: Boolean = true) extends HeapEntry with Cloneable {
  override def clone: ObjectRep = super.clone.asInstanceOf[ObjectRep]

  def fresh: ObjectRep = {
    val obj = new ObjectRep(tp, open)
    obj._syms = this._syms
    obj._classInfos = this._classInfos
    heap.add(obj)
    obj
  }

  /** inner class definitions */
  private var _classInfos: Map[ClassSymbol, (Template, Int)] = List()
  def add(cls: ClassSymbol, info: (Template, Int)) = _classInfos(cls) = info
  def classInfos: Map[ClassSymbol, (Template, Int)] = _classInfos

  /** methods and fields of the object */
  private var _syms: Map[Symbol, SymInfo] = Map()

  def apply(sym: Symbol): SymInfo =
    _syms(sym)

  def add(sym: Symbol, info: SymInfo) =
    _syms = _syms.updated(sym, info)

  // object environment should not resolve outer
  def update(sym: Symbol, info: SymInfo): Unit = {
    assert(_syms.contains(sym))
    _syms = _syms.updated(sym, info)
  }

  def contains(sym: Symbol): Boolean =
    _syms.contains(sym)

  def notAssigned = _syms.keys.filter(sym => !(_syms(sym).assigned))
  def forcedSyms  = _syms.keys.filter(sym => _syms(sym).forced)

  // Invariant: two objects with the same id always have the same `classInfos`,
  //            thus they can be safely ignored in `join`.
  def join(obj2: ObjectRep): ObjectRep = {
    assert(this.id == obj2.id)

    _syms.foreach { case (sym: Symbol, info: SymInfo) =>
      assert(obj2.contains(sym))
      val info2 = obj2._syms(sym)
      // path-insensitive approximation:
      // 1. if a variable is assigned in one branch, but not in the other branch, we
      //    treat the variable as not assigned.
      // 2. a lazy variable is force if it's forced in either branch
      // 3. a variable gets the lowest possible state
      if (!info.assigned || !info2.assigned)
        _syms = _syms.updated(sym, info.copy(assigned = false, transparentValue = NoTransparent))
      else {
        val infoRes = info.copy(
          assigned   =  true,
          forced     =  info.forced || info2.forced,
          value      =  info.value.join(info2.value)
        )

        _syms = _syms.updated(sym, infoRes)
      }
    }

    this
  }

  override def equals(that: Any): Boolean = that match {
    case that: ObjectRep => that.id == this.id
    case _ => false
  }

  override def toString: String =
    s"""~ --------------${getClass} - $id($envId)-----------------------
        ~ | fields:  ${_syms.keys}
        ~ | not initialized:  ${notAssigned}
        ~ | lazy forced:  ${forcedSyms}
        ~ | class: $tp"""
    .stripMargin('~')
}

//=======================================
//           Res
//=======================================
import Effect._

case class Res(var effects: Effects = Vector.empty, var value: Value = FullValue) {
  def +=(eff: Effect): Unit = effects = effects :+ eff
  def ++=(effs: Effects) = effects ++= effs

  def hasErrors  = effects.size > 0

  def join(res2: Res): Res =
    Res(
      effects = res2.effects ++ this.effects,
      value   = res2.value.join(value)
    )

  override def toString: String =
    s"""~Res(
        ~| effects = ${if (effects.isEmpty) "()" else effects.mkString("\n|    - ", "\n|    - ", "")}
        ~| value   = $value
        ~)"""
    .stripMargin('~')
}

//=======================================
//             Effects
//=======================================

sealed trait Effect {
  def report(implicit ctx: Context): Unit = this match {
    case Uninit(sym, pos)         =>
      ctx.warning(s"Reference to uninitialized value `${sym.name}`", pos)
    case OverrideRisk(sym, pos)     =>
      ctx.warning(s"`@partial` or `@filled` is recommended for $sym for safe overriding", sym.pos)
      ctx.warning(s"Reference to $sym which could be overriden", pos)
    case Call(sym, effects, pos)  =>
      ctx.warning(s"The call to `${sym.name}` causes initialization problem", pos)
      effects.foreach(_.report)
    case Force(sym, effects, pos) =>
      ctx.warning(s"Forcing lazy val `${sym.name}` causes initialization problem", pos)
      effects.foreach(_.report)
    case Instantiate(cls, effs, pos)  =>
      ctx.warning(s"Create instance results in initialization errors", pos)
      effs.foreach(_.report)
    case UseAbstractDef(sym, pos)  =>
      ctx.warning(s"`@scala.annotation.init` is recommended for abstract $sym for safe initialization", sym.pos)
      ctx.warning(s"Reference to abstract $sym which should be annotated with `@scala.annotation.init`", pos)
    case Latent(tree, effs)  =>
      ctx.warning(s"Latent effects results in initialization errors", tree.pos)
      effs.foreach(_.report)
    case Generic(msg, pos) =>
      ctx.warning(msg, pos)

  }
}

case class Uninit(sym: Symbol, pos: Position) extends Effect                         // usage of uninitialized values
case class OverrideRisk(sym: Symbol, pos: Position) extends Effect                   // calling methods that are not override-free
case class Call(sym: Symbol, effects: Seq[Effect], pos: Position) extends Effect     // calling method results in error
case class Force(sym: Symbol, effects: Seq[Effect], pos: Position) extends Effect    // force lazy val results in error
case class Instantiate(cls: Symbol, effs: Seq[Effect], pos: Position) extends Effect // create new instance of in-scope inner class results in error
case class UseAbstractDef(sym: Symbol, pos: Position) extends Effect                 // use abstract def during initialization, see override5.scala
case class Latent(tree: tpd.Tree, effs: Seq[Effect]) extends Effect                  // problematic latent effects (e.g. effects of closures)
case class Generic(msg: String, pos: Position) extends Effect                        // generic problem

object Effect {
  type Effects = Vector[Effect]
}
