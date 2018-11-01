package dotty.tools
package dotc
package mirror

import core._
import typer._
import ast.{tpd, _}
import Trees._
import Types._
import Contexts._
import Constants._
import Names._
import Symbols._

object Evaluator {

  import tpd._

  /** partially evaluate mirror types */
  def reduce(tp: AppliedType)(cont: Type => Boolean)(implicit ctx: Context): Boolean = {
    val reduced: Type = reduce(tp)
    reduced != tp && cont(reduced)
  }

  def reduce(tp: Type)(implicit ctx: Context): Type = tp match {
    case AppliedType(typcon: TypeRef, args) =>
      val args2 = args.map(arg => reduce(arg))

      val reducible = args2.forall(_.isInstanceOf[ConstantType])

      if (reducible) {
        val sym = typcon.typeSymbol
        val expr = getTree(sym)
        val names = getNames(sym)

        if (expr.isEmpty) return tp

        val values = args.map { case ConstantType(const) => tpd.Literal(const) }
        val env = names.zip(values).toMap

        val treeMap = new TreeMap {
          override def transform(t: Tree)(implicit ctx: Context) = t match {
            case Ident(name) => env(name.asTermName)
            case _ => super.transform(t)
          }
        }
        val exprClosed = treeMap.transform(expr)
        eval(exprClosed).tpe
      } else AppliedType(typcon, args2)
    case _ =>
      tp
  }

  def eval(expr: Tree)(implicit ctx: Context): Tree = expr match {
    case If(cond, thenp, elsep) =>
      if (eval(cond).tpe =:= ConstantType(Constant(true))) eval(thenp)
      else eval(elsep)
    case Select(_, _) => ConstFold(expr)
    case app @ Apply(Select(xt, opt), yt :: Nil) =>
      (xt.tpe.widenTermRefExpr, yt.tpe.widenTermRefExpr) match {
        case (ConstantType(_), ConstantType(_)) => ConstFold(expr)
        case _ => ConstFold(app.copy(fun = Select(eval(xt), opt), eval(yt) :: Nil))
      }
    case _ => ConstFold(expr)
  }

  def getNames(sym: Symbol)(implicit ctx: Context): List[TermName] = {
    val methSym = sym.owner.asClass.typeRef.member(sym.name.toTermName).symbol
    methSym.info.paramNamess.flatten
  }

  def getTree(sym: Symbol)(implicit ctx: Context): Tree = {
    val methSym = sym.owner.asClass.typeRef.member(sym.name.toTermName).symbol
    methSym.unforcedAnnotation(defn.BodyAnnot) match {
      case Some(annot) => annot.tree
      case None => EmptyTree
    }
  }

}
