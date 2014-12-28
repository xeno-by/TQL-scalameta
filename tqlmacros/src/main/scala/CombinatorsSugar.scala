package tql

/**
 * Created by Eric on 26.10.2014.
 */

import scala.language.experimental.macros
import scala.reflect.macros.whitebox.Context

class CombinatorsSugar(val c: Context) {
  import c.universe._

  def filterSugarImpl[T : c.WeakTypeTag](f: c.Tree): c.Tree = {
    val (lhs, _) =  getLUBsfromPFs[T](f)
    q"${c.prefix}.guard[$lhs]($f)"
  }

  def TWithResult[T: c.WeakTypeTag, A : c.WeakTypeTag](a: c.Tree): c.Tree  = c.untypecheck(c.prefix.tree) match {
    case q"$_.CTWithResult[$_]($t)" => q"($t, $a)"
    case q"$_.CTWithResult[$_]($t).andCollect[$_]" => q"($t, $a)"
    case _ => c.abort(c.enclosingPosition, "Bad form in TWithResult " + show(c.prefix.tree))
  }

  def TAndCollect[T: c.WeakTypeTag, A : c.WeakTypeTag](a: c.Tree)(y: c.Tree): c.Tree = TWithResult[T, List[A]](q"($y.builder += $a).result")

  /**
   * Rewrite
   * transform {
   *    case Lit.Int(a) => Lit.Int(a * 3) andCollect a
   *    case Term.If(a, b, c) => Term.If(a, b, c)  andCollect 0
   * }
   * into
   *
   * transformWithResult[Lit, Lit, List[Int] ]({case Lit.Int(a) => Lit.Int(a * 3) andCollect a} |
   * transformWithResult[Term, Term, List[Int] ]({case Term.If(a, b, c) => Term.If(a, b, c)  andCollect 0} |
   *
   *Here's the kind of error you get if you try to do something like that:
   *
   * transform {
   *    case Lit.Int(a) => Lit.Int(a * 3) andCollect a
   *    case Term.If(a, b, c) => Term.If(a, b, c)  //no result here.
   * }.down
   *
   * [error] D:\[..]\scala\meta\tql\Example.scala:44: could not find implicit
   * value for evidence parameter of type tql.Monoid[Any]
   * */
  def transformSugarImpl[T : c.WeakTypeTag](f: c.Tree): c.Tree = {
    val Ttpe = implicitly[c.WeakTypeTag[T]].tpe

    def setTuplesForEveryOne(clauses: List[CaseDef]): List[CaseDef] = {
      def setTupleTo(rhs: c.Tree) = rhs.tpe match {
        case TypeRef(_, sym, _) if sym.fullName != "scala.Tuple2" => q"($rhs, _root_.tql.Monoid.Void)"
        case _ => rhs
      }
      clauses.map{_ match {
        case cq"${lhs: c.Tree} => ${rhs:  c.Tree}" => cq"$lhs => ${setTupleTo(rhs)}"
        case cq"${lhs: c.Tree} if $cond => ${rhs:  c.Tree}" => cq"$lhs if $cond => ${setTupleTo(rhs)}"
        case x => x
      }}
    }

    def getTypesFromTuple2(rhss: List[c.Type]): List[(c.Type, c.Type)] = rhss.map{
      /*It's not like I have a choice..
      http://stackoverflow.com/questions/18735295/how-to-match-a-universetype-via-quasiquotes-or-deconstructors*/
      case TypeRef(_, sym, List(a, b)) if sym.fullName == "scala.Tuple2" => (a, b)
      case x if x <:< Ttpe => (x, typeOf[Unit])
      case _ => c.abort(c.enclosingPosition, "There should be a Tuple2 or a " + show(Ttpe) + " here")
    }

    def normalizeCases(pf: c.Tree) = pf match {
      case q"{case ..$cases}" => setTuplesForEveryOne(cases).map(betterUntypecheck(_))
    }

    val cases = normalizeCases(f)
    val (lhss, rhss) = getTypesFromPFS[T](f).unzip
    val (trhs, ress) = getTypesFromTuple2(rhss).unzip

    val transforms = cases.zip(lhss).zip(trhs).zip(ress).map{
      case (((cas, lhs), rhs), res) =>
      q"transformWithResult[$lhs, $rhs, $res](PartialFunction[$lhs, ($rhs, $res)]({case $cas}))"
    }

    if (transforms.size < 1)
      c.abort(c.enclosingPosition, "No cases found in " + show(f))


    transforms.reduceRight[c.Tree]((c, acc) => q"$c | $acc")
  }

  def transformSugarImplWithTRtype[T : c.WeakTypeTag](f: c.Tree): c.Tree =
    q"${c.prefix}.transforms(${transformSugarImpl[T](f)})"


  def getLUBsfromPFs[T : c.WeakTypeTag](f: c.Tree): (c.Type, c.Type) = {
    val tpes = getTypesFromPFS[T](f)
    if (tpes.size > 1){
      val (lhs, rhs) = tpes.unzip
      (lub(lhs), lub(rhs))
    }
    else
      tpes.head //TODO guarenteed to have size > 0?
  }

  def getTypesFromPFS[T : c.WeakTypeTag](f: c.Tree): List[(c.Type, c.Type)] = {
    f match {
      case q"{case ..$cases}" =>
        cases.map(getTypesFromCase(_))
      case func if func.tpe <:< weakTypeOf[PartialFunction[_, _]] =>
        val lhs :: rhs :: Nil = func.tpe.typeArgs
        List((implicitly[c.WeakTypeTag[T]].tpe, rhs))
      case _ => c.abort(c.enclosingPosition, "Expecting a partial function here")
    }
  }

  def getTypesFromCase(cas: c.Tree): (c.Type, c.Type) = {
    import c.universe._
    cas match {
      case cq"${lhs: c.Tree} => ${rhs:  c.Tree}" => (lhs.tpe, rhs.tpe)
      case cq"${lhs: c.Tree} if $_ => ${rhs:  c.Tree}" => (lhs.tpe, rhs.tpe)
      case p => c.abort(c.enclosingPosition, "Bad format in partial function at: " + show(p))
    }
  }

  /**
   * When type checking a partial function an <unapply-selector> thing gets added in the AST of the pattern match
   * and this thing cannot be typechecked a second time. (see : https://issues.scala-lang.org/browse/SI-5465)
   * To solve this problem we have to remonve the <unapply-selector> ourselves, and here is the solution:
   * thanks Eugene : https://gist.github.com/xeno-by/7fbd422c6789299140a7*/
  object betterUntypecheck extends Transformer {
    override def transform(tree: Tree): Tree = tree match {
      case UnApply(Apply(Select(qual, TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), args) =>
        Apply(transform(qual), transformTrees(args))
      case _ => super.transform(tree)
    }
    def apply(tree: Tree): Tree =  c.untypecheck(transform(tree))
  }
}
