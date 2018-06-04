package dotty.tools.dotc
package transform

import core._
import MegaPhase._
import Contexts.Context
import StdNames._
import Phases._
import ast._
import Trees._
import Flags._
import SymUtils._
import Symbols._
import SymDenotations._
import Types._
import Decorators._
import DenotTransformers._
import util.Positions._
import config.Printers.init.{ println => debug }
import Constants.Constant
import collection.mutable

object InitChecker {
  val name = "initChecker"
}

import DataFlowChecker._

/** This transform checks initialization is safe based on data-flow analysis
 *
 *  Partial values:
 *   - partial values cannot be used as full values
 *   - a partial value can only be assigned to uninitialized field of a partial value
 *   - selection on a partial value is an error, unless the accessed field is known to be fully initialized
 *
 *  Init methods:
 *   - methods called during initialization should be annotated with `@init` or non-overridable
 *   - an `@init` method should not call overridable non-init methods
 *   - an overriding or implementing `@init` may only access param fields or other init-methods on `this`
 *   - otherwise, it may access non-param fields on `this`
 *
 *  Partial values are defined as follows:
 *   - params with the type `T @partial`
 *   - `this` in constructor unless it's known to be fully initialized
 *   - `new C(args)`, if any argument is partial
 *   - `val x = rhs` where the right-hand-side is partial
 *
 *  TODO:
 *   - default arguments of partial/init methods
 *   - selection on ParamAccessors of partial value is fine if the param is not partial
 *   - handle tailrec calls during initialization (which captures `this`)
 *
 *  TODO:
 *   - parent call abstract methods during initialization
 *      - scala/tools/nsc/backend/jvm/BackendInterface.scala:722
 *      - scala/tools/nsc/backend/jvm/BCodeBodyBuilder.scala:1451
 *      - scala/tools/nsc/backend/jvm/BackendInterface.scala:727
 *      - compiler/src/dotty/tools/dotc/config/Properties.scala:23
 *      - compiler/src/dotty/tools/dotc/config/Properties.scala:69
 *      - compiler/src/dotty/tools/dotc/core/Denotations.scala:1162
 *      - compiler/src/dotty/tools/io/AbstractFile.scala:97
 *   - child call parent method during initialization
 *      - compiler/src/dotty/tools/dotc/config/ScalaSettings.scala:16
 *      - compiler/src/dotty/tools/dotc/ast/DesugarEnums.scala:19
 *      - compiler/src/dotty/tools/dotc/core/Contexts.scala:573
 *      - compiler/src/dotty/tools/dotc/core/StdNames.scala:63
 *      - compiler/src/dotty/tools/dotc/core/unpickleScala2/Scala2Unpickler.scala:236
 *      - compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala:34
 *      - compiler/src/dotty/tools/dotc/parsing/JavaTokens.scala:18
 *      - compiler/src/dotty/tools/dotc/typer/Applications.scala:546
 *   - select field of parent
 *      - compiler/src/dotty/tools/dotc/ast/Desugar.scala:82
 *      - compiler/src/dotty/tools/dotc/core/Contexts.scala:573
 *      - compiler/src/dotty/tools/dotc/core/Comments.scala:120
 *      - compiler/src/dotty/tools/dotc/core/tasty/TreeBuffer.scala:14
 *   - `this` escape as full value
 *      - compiler/src/dotty/tools/dotc/Run.scala:61
 *      - compiler/src/dotty/tools/dotc/core/Contexts.scala:552
 *      - compiler/src/dotty/tools/dotc/core/Types.scala:2353
 *      - compiler/src/dotty/tools/dotc/core/Types.scala:3054
 *      - compiler/src/dotty/tools/dotc/core/Types.scala:3073
 *      - compiler/src/dotty/tools/dotc/core/tasty/TastyPickler.scala:73
 *      - compiler/src/dotty/tools/dotc/typer/RefChecks.scala:851
 *   - `this` escape in a partial closure
 *      - compiler/src/dotty/tools/dotc/core/Definitions.scala:855
 *      - compiler/src/dotty/tools/dotc/transform/LambdaLift.scala:354
 *      - compiler/src/dotty/tools/dotc/transform/MegaPhase.scala:402
 *   - init methods not final or private
 *      - compiler/src/dotty/tools/dotc/ast/Positioned.scala:118
 *      - compiler/src/dotty/tools/dotc/ast/Positioned.scala:54
 *      - compiler/src/dotty/tools/dotc/parsing/CharArrayReader.scala:10
 *      - compiler/src/dotty/tools/dotc/parsing/JavaScanners.scala:534
 *   - `this` escape in val overriding method
 *      - compiler/src/dotty/tools/dotc/core/Contexts.scala:538
 *   - create instance of nested class
 *      - compiler/src/dotty/tools/dotc/core/NameKinds.scala:68
 *   - fix me
 *      - compiler/src/dotty/tools/dotc/core/NameKinds.scala:88
 *      - compiler/src/dotty/tools/dotc/core/SymDenotations.scala:1355
 *      - compiler/src/dotty/tools/dotc/reporting/diagnostic/messages.scala:1342 default methods
 */
class InitChecker extends MiniPhase with IdentityDenotTransformer { thisPhase =>
  import tpd._

  override def phaseName: String = InitChecker.name

  override def transformDefDef(tree: tpd.DefDef)(implicit ctx: Context): tpd.Tree = {
    tree
  }

  override def transformTemplate(tree: Template)(implicit ctx: Context): Tree = {
    val cls = ctx.owner.asClass

    if (cls.hasAnnotation(defn.UncheckedAnnot)) return tree

    val accessors = cls.paramAccessors.filterNot(x => x.isSetter)

    var noninit = Set[Symbol]()    // definitions that are not initialized
    var partial = Set[Symbol]()    // definitions that are partial initialized

    def isPartial(sym: Symbol) = sym.info.hasAnnotation(defn.PartialAnnot)

    def isConcreteField(sym: Symbol) =
      sym.isTerm && sym.is(AnyFlags, butNot = Deferred | Method | Local | Private)

    def isNonParamField(sym: Symbol) =
      sym.isTerm && sym.is(AnyFlags, butNot = Method | ParamAccessor | Lazy | Deferred)

    // partial fields of current class
    for (
      param <- accessors
      if isPartial(param)
    )
    partial += param

    // partial fields of super class
    for (
      parent <- cls.baseClasses.tail;
      decl <- parent.info.decls.toList
      if isConcreteField(decl) && isPartial(decl)
    )
    partial += decl

    // add current this
    partial += cls

    // non-initialized fields of current class
    for (
      decl <- cls.info.decls.toList
      if isNonParamField(decl)
    )
    noninit += decl

    val env: Env = (new FreshEnv).setNonInit(noninit).setPartialSyms(partial).setCurrentClass(cls)
    val checker = new DataFlowChecker

    val res = checker(env, tree)
    res.effects.foreach(_.report)
    res.env.nonInit.foreach { sym =>
      ctx.warning(s"field ${sym.name} is not initialized", sym.pos)
    }

    tree
  }
}

object DataFlowChecker {
  sealed trait Effect {
    def report(implicit ctx: Context): Unit = this match {
      case Member(sym, obj, pos)    =>
        ctx.warning(s"Select $sym on partial value ${obj.show}", pos)
      case Uninit(sym, pos)         =>
        ctx.warning(s"Reference to uninitialized value `${sym.name}`", pos)
      case OverrideRisk(sym, pos)     =>
        ctx.warning(s"`@scala.annotation.init` is recommended for abstract $sym for safe initialization", sym.pos)
        ctx.warning(s"Reference to $sym which could be overriden", pos)
      case Call(sym, effects, pos)  =>
        ctx.warning(s"The call to `${sym.name}` causes initialization problem", pos)
        effects.foreach(_.report)
      case Force(sym, effects, pos) =>
        ctx.warning(s"Forcing lazy val `${sym.name}` causes initialization problem", pos)
        effects.foreach(_.report)
      case Argument(sym, arg)       =>
        ctx.warning(s"Use partial value ${arg.show} as a full value to ${sym.show}", arg.pos)
      case CrossAssign(lhs, rhs)    =>
        ctx.warning(s"Assign partial value to a non-partial value", rhs.pos)
      case PartialNew(prefix, cls, pos)  =>
        ctx.warning(s"Cannot create $cls because the prefix `${prefix.show}` is partial", pos)
      case Instantiate(cls, effs, pos)  =>
        ctx.warning(s"Create instance results in initialization errors", pos)
        effs.foreach(_.report)
      case UseAbstractDef(sym, pos)  =>
         ctx.warning(s"`@scala.annotation.init` is recommended for abstract $sym for safe initialization", sym.pos)
         ctx.warning(s"Reference to abstract $sym which should be annotated with `@scala.annotation.init`", pos)
      case Latent(tree, effs)  =>
        effs.foreach(_.report)
        ctx.warning(s"Latent effects results in initialization errors", tree.pos)
    }
  }
  case class Uninit(sym: Symbol, pos: Position) extends Effect                         // usage of uninitialized values
  case class OverrideRisk(sym: Symbol, pos: Position) extends Effect                   // calling methods that are not override-free
  case class Call(sym: Symbol, effects: Seq[Effect], pos: Position) extends Effect     // calling method results in error
  case class Force(sym: Symbol, effects: Seq[Effect], pos: Position) extends Effect    // force lazy val results in error
  case class Argument(fun: Symbol, arg: tpd.Tree) extends Effect                       // use of partial values as full values
  case class Member(sym: Symbol, obj: tpd.Tree, pos: Position) extends Effect          // select members of partial values
  case class CrossAssign(lhs: tpd.Tree, rhs: tpd.Tree) extends Effect                  // assign a partial values to non-partial value
  case class PartialNew(prefix: Type, cls: Symbol, pos: Position) extends Effect       // create new inner class instance while outer is partial
  case class Instantiate(cls: Symbol, effs: Seq[Effect], pos: Position) extends Effect // create new instance of in-scope inner class results in error
  case class UseAbstractDef(sym: Symbol, pos: Position) extends Effect                 // use abstract def during initialization, see override5.scala
  case class Latent(tree: tpd.Tree, effs: Seq[Effect]) extends Effect                  // problematic latent effects (e.g. effects of closures)

  object NewEx {
    def extract(tp: Type)(implicit ctx: Context): TypeRef = tp.dealias match {
      case tref: TypeRef => tref
      case AppliedType(tref: TypeRef, targs) => tref
    }

    def unapply(tree: tpd.Tree)(implicit ctx: Context): Option[(TypeRef, TermRef, List[List[tpd.Tree]])] = {
      val (fn, targs, vargss) = tpd.decomposeCall(tree)
      if (!fn.symbol.isConstructor || !tree.isInstanceOf[tpd.Apply]) None
      else {
        val Select(New(tpt), _) = fn
        Some((extract(tpt.tpe),  fn.tpe.asInstanceOf[TermRef], vargss))
      }
    }
  }

  type Effects = Vector[Effect]
  type LatentEffects = Env => Vector[Effect]
  // TODO: handle curried functions & methods uniformly
  // type LatentEffects = Env => (Vector[Effect], LatentEffects)

  class Env extends Cloneable {
    protected var _nonInit: Set[Symbol] = Set()
    protected var _partialSyms: Set[Symbol] = Set()
    protected var _lazyForced: Set[Symbol] = Set()
    protected var _latentSyms: Map[Symbol, LatentEffects] = Map()
    protected var _cls: ClassSymbol = null
    protected var _defns: Map[Symbol, tpd.Tree] = Map()
    protected var _methChecking: Set[Symbol] = Set()

    def fresh: FreshEnv = this.clone.asInstanceOf[FreshEnv]

    def currentClass = _cls

    def isPartial(sym: Symbol)    = _partialSyms.contains(sym)
    def addPartial(sym: Symbol)   = _partialSyms += sym
    def removePartial(sym: Symbol)   = _partialSyms -= sym
    def partialSyms: Set[Symbol]  = _partialSyms

    def isLatent(sym: Symbol)     = _latentSyms.contains(sym)
    def addLatent(sym: Symbol, effs: LatentEffects) = _latentSyms += sym -> effs
    def latentEffects(sym: Symbol): LatentEffects = _latentSyms(sym)

    def isForced(sym: Symbol)     = _lazyForced.contains(sym)
    def addForced(sym: Symbol)    = _lazyForced += sym
    def lazyForced: Set[Symbol]   = _lazyForced

    def isNotInit(sym: Symbol)       = _nonInit.contains(sym)
    def addInit(sym: Symbol)      = _nonInit -= sym
    def nonInit: Set[Symbol]      = _nonInit

    def isChecking(sym: Symbol)   = _methChecking.contains(sym)
    def addChecking(sym: Symbol)  = _methChecking += sym
    def removeChecking(sym: Symbol) = _methChecking -= sym
    def checking[T](sym: Symbol)(fn: => T) = {
      addChecking(sym)
      val res = fn
      removeChecking(sym)
      res
    }

    def initialized: Boolean      = _nonInit.isEmpty && _partialSyms.size == 1
    def markInitialized           = {
      assert(initialized)
      _partialSyms = Set()
    }

    def addDef(sym: Symbol, tree: tpd.Tree) = _defns += sym -> tree
    def removeDef(sym: Symbol)    = _defns -= sym
    def addDefs(defs: List[(Symbol, tpd.Tree)]) = _defns ++= defs
    def removeDefs(defs: List[Symbol]) = _defns --= defs
    def defn(sym: Symbol)         = _defns(sym)

    def show(padding: String): String =
      s"""~ $padding | ------------ $currentClass -------------
          ~ $padding | not initialized:  ${_nonInit}
          ~ $padding | partial initialized: ${_partialSyms}
          ~ $padding | lazy forced:  ${_lazyForced}
          ~ $padding | latent symbols: ${_latentSyms.keys}"""
      .stripMargin('~')
  }

  class FreshEnv extends Env {
    def setPartialSyms(partialSyms: Set[Symbol]): this.type = { this._partialSyms = partialSyms; this }
    def setNonInit(nonInit: Set[Symbol]): this.type = { this._nonInit = nonInit; this }
    def setLazyForced(lazyForced: Set[Symbol]): this.type = { this._lazyForced = lazyForced; this }
    def setCurrentClass(cls: ClassSymbol): this.type = { this._cls = cls; this }
  }

  case class Res(
    val env: Env,
    var effects: Effects = Vector.empty,
    var partial: Boolean = false,
    var latentEffects: LatentEffects = null
  ) {

    def force(env: Env): Effects = latentEffects(env)
    def isLatent = latentEffects != null

    def +=(eff: Effect): Unit = effects = effects :+ eff
    def ++=(effs: Effects) = effects ++= effs

    def derived(effects: Effects = effects, partial: Boolean = partial, latentEffects: LatentEffects = latentEffects, env: Env = env): Res =
      Res(env, effects, partial, latentEffects)

    def show(padding: String): String =
      s"""~Res(
          ~${env.show(padding)}
          ~ $padding | effects = $effects
          ~ $padding | partial = $partial
          ~ $padding | latent  = $isLatent
          ~ $padding)"""
      .stripMargin('~')
  }
}

class DataFlowChecker {

  import tpd._

  var depth: Int = 0
  val indentTab = " "

  def trace(msg: String)(body: => Res) = {
    indentedDebug(s" ==> $msg?")
    depth += 1
    val res = body
    depth -= 1
    indentedDebug(s" <== $msg = ${res.show(padding)}")
    res
  }

  def padding = indentTab * depth

  def indentedDebug(msg: String) =
    debug(s"${padding}$msg")

  def checkForce(sym: Symbol, tree: Tree, env: Env)(implicit ctx: Context): Res =
    if (sym.is(Lazy) && !env.isForced(sym)) {
      env.addForced(sym)
      val rhs = env.defn(sym)
      val res = apply(env, rhs)

      if (res.partial) res.env.addPartial(sym)
      if (res.isLatent) res.env.addLatent(sym, res.latentEffects)

      if (res.effects.nonEmpty) res.derived(effects = Vector(Force(sym, res.effects, tree.pos)))
      else res
    }
    else Res(env = env)

  def checkCall(fun: TermRef, tree: Tree, env: Env)(implicit ctx: Context): Res = {
    val sym = fun.symbol
    if (env.isChecking(sym)) {
      debug("recursive call found during initialization")
      Res(env)
    }
    else {
      var effs = Vector.empty[Effect]
      if (!(sym.hasAnnotation(defn.InitAnnot) || sym.isEffectivelyFinal || isDefaultGetter(sym)))
        effs = effs :+ OverrideRisk(sym, tree.pos)

      env.checking(sym) {
        val rhs = env.defn(sym)
        val res = apply(env, rhs)

        if (res.effects.nonEmpty) res.derived(effects = Vector(Call(sym, res.effects ++ effs, tree.pos)))
        else res.derived(effects = effs)
      }
    }
  }

  def checkParams(sym: Symbol, paramInfos: List[Type], args: List[Tree], env: Env)(implicit ctx: Context): Res = {
    def isParamPartial(index: Int) = paramInfos(index).hasAnnotation(defn.PartialAnnot)

    var effs = Vector.empty[Effect]
    var partial = false

    args.zipWithIndex.foreach { case (arg, index) =>
      val res = apply(env, arg)
      effs ++= res.effects
      partial = partial || res.partial


      if (res.isLatent) {
        val effs2 = res.latentEffects.apply(env)                   // latent values are not partial
        if (effs2.nonEmpty) {
          partial = true
          if (!isParamPartial(index)) effs = effs :+ Latent(arg, effs2)
        }
      }
      else if (res.partial && !isParamPartial(index)) effs = effs :+ Argument(sym, arg)
    }

    Res(env = env, effects = effs, partial = partial)
  }

  def checkNew(tree: Tree, tref: TypeRef, init: TermRef, argss: List[List[tpd.Tree]], env: Env)(implicit ctx: Context): Res = {
    val paramInfos = init.widen.paramInfoss.flatten
    val args = argss.flatten

    val res1 = checkParams(tref.symbol, paramInfos, args, env)

    if (!isPartial(tref.prefix, env) || isSafeParentAccess(tref, res1.env)) return res1

    if (!isLexicalRef(tref, res1.env)) {
      res1 += PartialNew(tref.prefix, tref.symbol, tree.pos)
      res1
    }
    else {
      if (env.isChecking(tref.symbol)) {
        debug("recursive call found during initialization")
        res1
      }
      else res1.env.checking(tref.symbol) {
        val tmpl = env.defn(tref.symbol)
        val res2 = apply(res1.env, tmpl)
        if (res2.effects.nonEmpty) res1 += Instantiate(tref.symbol, res2.effects, tree.pos)
        res1.derived(env = res2.env)
      }
    }
  }

  def checkApply(tree: tpd.Tree, fun: Tree, args: List[Tree], env: Env)(implicit ctx: Context): Res = {
    val res1 = apply(env, fun)

    val paramInfos = fun.tpe.widen.asInstanceOf[MethodType].paramInfos
    val res2 = checkParams(fun.symbol, paramInfos, args, res1.env)

    var effs = res1.effects ++ res2.effects

    // function apply: see TODO for LatentEffects
    if (res1.isLatent) {
      val effs2 = res1.latentEffects.apply(res2.env)
      if (effs2.nonEmpty) effs = effs :+ Latent(tree, effs2)
    }

    if (!tree.tpe.isInstanceOf[MethodOrPoly]) {     // check when fully applied
      val mt = methPart(tree)
      mt.tpe match {
        case ref: TermRef if isPartial(ref.prefix, env) && isLexicalRef(ref, env) =>
          val res3 = checkCall(ref, tree, res2.env)
          return res3.derived(effects = effs ++ res3.effects)
        case _ =>
      }
    }

    Res(env = res2.env, effects = effs)
  }

  def checkSelect(tree: Select, env: Env)(implicit ctx: Context): Res = {
    val res = apply(env, tree.qualifier)

    // TODO: add latent effects of methods here
    if (res.partial)
      res += Member(tree.symbol, tree.qualifier, tree.pos)

    res
  }

  /** return the top-level local term within `cls` refered by `tp`, NoType otherwise.
   *
   *  There are following cases:
   *   - select on this: `C.this.x`
   *   - select on super: `C.super[Q].x`
   *   - local ident: `x`
   *   - select on self: `self.x` (TODO)
   */
  def localRef(tp: Type, env: Env)(implicit ctx: Context): Type = tp match {
    case TermRef(ThisType(tref), _) if tref.symbol.isContainedIn(env.currentClass) => tp
    case TermRef(SuperType(ThisType(tref), _), _) if tref.symbol.isContainedIn(env.currentClass) => tp
    case ref @ TermRef(NoPrefix, _) if ref.symbol.isContainedIn(env.currentClass) => ref
    case TermRef(tp: TermRef, _) => localRef(tp, env)
    case _ => NoType
  }

  object NamedTypeEx {
    def unapply(tp: Type)(implicit ctx: Context): Option[(Type, Symbol)] = tp match {
      case ref: TermRef => Some(ref.prefix -> ref.symbol)
      case ref: TypeRef => Some(ref.prefix -> ref.symbol)
      case _ => None
    }
  }

  /** Does the NamedType refer to a symbol defined within `cls`? */
  def isLexicalRef(tp: NamedType, env: Env)(implicit ctx: Context): Boolean =
    tp.symbol.isContainedIn(env.currentClass)

  /** Is the NamedType a reference to safe member defined in the parent of `cls`?
   *
   *  A member access is safe in the following cases:
   *  - a non-lazy, non-deferred field where the primary constructor takes no partial values
   *  - a method marked as `@init`
   *  - a class marked as `@init`
   */
  def isSafeParentAccess(tp: NamedType, env: Env)(implicit ctx: Context): Boolean =
    tp.symbol.owner.isClass && (env.currentClass.isSubClass(tp.symbol.owner) || env.currentClass.givenSelfType.classSymbols.exists(_.isSubClass(tp.symbol.owner))) &&
      (
        tp.symbol.isTerm && tp.symbol.is(AnyFlags, butNot = Method | Lazy | Deferred) && !hasPartialParam(tp.symbol.owner, env) ||
        tp.symbol.hasAnnotation(defn.InitAnnot) || tp.symbol.hasAnnotation(defn.PartialAnnot) ||
        isDefaultGetter(tp.symbol) || (env.initialized && env.currentClass.is(Final))
      )

  // TODO: default methods are not necessarily safe, if they call other methods
  def isDefaultGetter(sym: Symbol)(implicit ctx: Context) = sym.name.is(NameKinds.DefaultGetterName)

  def hasPartialParam(clazz: Symbol, env: Env)(implicit ctx: Context): Boolean =
    env.currentClass.paramAccessors.exists(_.hasAnnotation(defn.PartialAnnot))

  def isPartial(tp: Type, env: Env)(implicit ctx: Context): Boolean = tp match {
    case tmref: TermRef             => env.isPartial(tmref.symbol)
    case ThisType(tref)             => env.isPartial(tref.symbol)
    case SuperType(thistp, _)       => isPartial(thistp, env)        // super is partial if `thistp` is partial
    case _                          => false
  }

  def checkTermRef(tree: Tree, env: Env)(implicit ctx: Context): Res = {
    indentedDebug(s"is ${tree.show} local ? = " + localRef(tree.tpe, env).exists)
    val ref: TermRef = localRef(tree.tpe, env) match {
      case NoType         => return Res(env = env)
      case tmref: TermRef => tmref
    }

    val res = Res(env = env)

    if (env.isPartial(ref.symbol)) res.partial = true
    if (env.isLatent(ref.symbol)) res.latentEffects = env.latentEffects(ref.symbol)

    if (isLexicalRef(ref, env)) {
      if (env.isNotInit(ref.symbol)) res += Uninit(ref.symbol, tree.pos)

      if (ref.symbol.is(Lazy)) {    // a forced lazy val could be partial and latent
        val res2 = checkForce(ref.symbol, tree, env)
        res2.partial ||= res.partial
        if (res.isLatent) res2.latentEffects = res.latentEffects
        return res2.derived(effects = res.effects ++ res2.effects)
      }
      else if (ref.symbol.is(Method) && ref.symbol.info.isInstanceOf[ExprType]) { // parameter-less call
        val res2 = checkCall(ref, tree, env)
        return res2.derived(effects = res.effects ++ res2.effects)
      }
      else if (ref.symbol.is(Deferred) && !ref.symbol.hasAnnotation(defn.InitAnnot) && ref.symbol.owner == env.currentClass) {
        res += UseAbstractDef(ref.symbol, tree.pos)
      }
    }
    else if (isPartial(ref.prefix, env) && !isSafeParentAccess(ref, env)) {
      res += Member(ref.symbol, tree, tree.pos)
    }

    res
  }

  def checkClosure(sym: Symbol, tree: Tree, env: Env)(implicit ctx: Context): Res = {
    val body = env.defn(sym)
    Res(
      env = env,
      latentEffects = (env: Env) => apply(env, body).effects,    // TODO: keep res
      partial = false
    )
  }

  def checkIf(tree: If, env: Env)(implicit ctx: Context): Res = {
    val If(cond, thenp, elsep) = tree

    val res1: Res = apply(env, cond)
    val res2: Res = apply(res1.env.fresh, thenp)
    val res3: Res = apply(res1.env.fresh, elsep)

    val env4 = res3.env.fresh
    env4.setNonInit(res2.env.nonInit ++ res3.env.nonInit)
    env4.setLazyForced(res2.env.lazyForced ++ res3.env.lazyForced)
    env4.setPartialSyms(res2.env.partialSyms ++ res3.env.partialSyms)

    val res = Res(env = env4)
    res.effects = res1.effects ++ res2.effects ++ res3.effects
    res.partial = res1.partial || res1.partial
    res.latentEffects = (env: Env) => res2.force(env) ++ res3.force(env)

    res
  }

  def apply(env: Env, tree: Tree)(implicit ctx: Context): Res = trace("checking " + tree.show)(tree match {
    case tmpl: Template =>
      val stats = tmpl.body.filter {
        case vdef : ValDef  =>
          !vdef.symbol.hasAnnotation(defn.ScalaStaticAnnot)
        case stat =>
          true
      }
      apply(env, Block(stats, tpd.unitLiteral))
    case vdef : ValDef if !vdef.symbol.is(Lazy) =>
      val res1 = apply(env, vdef.rhs)

      if (!tpd.isWildcardArg(vdef.rhs) && !vdef.rhs.isEmpty) res1.env.addInit(vdef.symbol)  // take `_` as uninitialized, otherwise it's initialized

      if (res1.partial) {
        if (res1.env.initialized) // fully initialized
          res1.env.markInitialized
        else
          res1.env.addPartial(vdef.symbol)
      }

      if (res1.isLatent)
        res1.env.addLatent(vdef.symbol, res1.latentEffects)

      res1.latentEffects = null

      res1
    case _: DefTree =>  // ignore other definitions
      Res(env = env)
    case Closure(_, meth, _) =>
      checkClosure(meth.symbol, tree, env)
    case tree: Ident if tree.symbol.isTerm =>
      checkTermRef(tree, env)
    case tree @ Select(prefix @ (This(_) | Super(_, _)), _) if tree.symbol.isTerm =>
      checkTermRef(tree, env)
    case tree @ NewEx(tref, init, argss) =>
      checkNew(tree, tref, init, argss, env)
    case tree @ Select(prefix, _) if tree.symbol.isTerm =>
      checkSelect(tree, env)
    case tree @ This(_) =>
      if (env.isPartial(tree.symbol) && !env.initialized) Res(env = env, partial = true)
      else Res(env = env)
    case tree @ Super(qual, mix) =>
      if (env.isPartial(qual.symbol) && !env.initialized) Res(env = env, partial = true)
      else Res(env = env)
    case tree @ If(cond, thenp, elsep) =>
      checkIf(tree, env)
    case tree @ Apply(fun, args) =>
      checkApply(tree, fun, args, env)
    case tree @ Assign(lhs @ (Ident(_) | Select(This(_), _)), rhs) =>
      val resRhs = apply(env, rhs)

      if (!resRhs.partial || env.isPartial(lhs.symbol) || env.isNotInit(lhs.symbol)) {
        resRhs.env.addInit(lhs.symbol)
        if (!resRhs.partial) resRhs.env.removePartial(lhs.symbol)
        else resRhs.env.addPartial(lhs.symbol)
      }
      else resRhs += CrossAssign(lhs, rhs)

      resRhs.latentEffects = null
      resRhs.partial = false

      resRhs
    case tree @ Assign(lhs @ Select(prefix, _), rhs) =>
      val resLhs = apply(env, prefix)
      val resRhs = apply(resLhs.env, rhs)

      val res = Res(env = resRhs.env, effects = resLhs.effects ++ resRhs.effects)

      if (resRhs.partial && !resLhs.partial)
        res += CrossAssign(lhs, rhs)

      res
    case tree @ Block(stats, expr) =>
      val meths = stats.collect {
        case ddef: DefDef if ddef.symbol.is(AnyFlags, butNot = Accessor) =>
          ddef.symbol -> ddef.rhs
        case vdef: ValDef if vdef.symbol.is(Lazy)  =>
          vdef.symbol -> vdef.rhs
        case tdef: TypeDef if tdef.isClassDef  =>
          tdef.symbol -> tdef.rhs
      }

      env.addDefs(meths)

      val res = stats.foldLeft(Res(env = env)) { (acc, stat) =>
        indentedDebug(s"acc = ${acc.show(padding)}")
        val res1 = apply(acc.env, stat)
        acc.derived(env = res1.env, effects = acc.effects ++ res1.effects)
      }

      val res1 = apply(res.env, expr)

      res1.env.removeDefs(meths.map(_._1))

      res1.derived(effects = res.effects ++ res1.effects)
    case Typed(expr, tpd) =>
      apply(env, expr)
    case _ =>
      Res(env = env)
  })
}