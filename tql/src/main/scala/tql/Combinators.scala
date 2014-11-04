package tql

/**
 * Created by Eric on 20.10.2014.
 */

trait Combinators[T] { self: Traverser[T] =>

  import scala.reflect.ClassTag

  /**
   * Traverse the children of the tree
   * */
  def children[A : Monoid](f: TreeMapper[A]) =  TreeMapper[A]{ tree =>
    traverse(tree, f)
  }

  /**
   * Traverse the tree in a TopDown manner, stop when a transformation/traversal has succeeded
   * */
  def downBreak[A : Monoid](m: TreeMapper[A]): TreeMapper[A] =
    m | children(downBreak[A](m))

  /**
   * Traverse the tree in a BottomUp manner, stop when a transformation/traversal has succeeded
   * */
  def upBreak[A : Monoid](m: TreeMapper[A]): TreeMapper[A] =
    children(upBreak[A](m)) | m

  /**
   * Same as TopDown, but does not sop when a transformation/traversal has succeeded
   * */
  def down[A : Monoid](m: TreeMapper[A]): TreeMapper[A] =
    m + children(down[A](m))

  /**
   * Same as upBreak, but does not sop when a transformation/traversal has succeeded
   * */
  def up[A : Monoid](m: TreeMapper[A]): TreeMapper[A] =
    children(up[A](m)) + m

  def flatMap[B](f: T => MatcherResult[B]) = TreeMapper[B] {tree =>
    f(tree)
  }


  /**
   * Succeed if the partial function f applied on the tree is defined and return true
   * */
  def guard[U <: T : ClassTag](f: PartialFunction[U, Boolean]) = TreeMapper[U]{
    case t: U if f.isDefinedAt(t) && f(t) => Some((t, t))
    case _ => None
  }

  /**
   * Same as filter but puts the results into a list
   * */
  def collect[A](f: PartialFunction[T, A])(implicit x: ClassTag[T]):TreeMapper[List[A]] =
    guard[T]{case t => f.isDefinedAt(t)} map (x => List(f(x)))

  /**
   *  Transform a I into a T where both I and O are subtypes of T and where a transformation from I to O is authorized
   * */
  def transform[I <: T : ClassTag, O <: T](f: PartialFunction[I, O])(implicit x: AllowedTransformation[I, O]) =
    TreeMapper[Unit] {
      case t: I if f.isDefinedAt(t) => Some((f(t), Monoid.Void.zero))
      case _ => None
    }

  /*def stateful[A: Monoid](f: A => TreeMapper[A]) = {
    import MonoidEnhencer._
    var value: A = implicitly[Monoid[A]].zero
    TreeMapper[A] { tree =>
      f(value)(tree) match {
        case s @ Some((v, a)) =>
          value += a
          s
        case None => None
      }
    }
  } */

  def stateful[A: Monoid](f: PartialFunction[(T, A), (T, A)]): PartialFunction[T, T] = {
    import MonoidEnhencer._
    var value: A = implicitly[Monoid[A]].zero
    def retFunc: PartialFunction[T, T] = {
      case t if f.isDefinedAt(t, value) =>
        val (tree, v) = f(t, value)
        value = v
        tree
    }
    retFunc
  }



}