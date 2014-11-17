package tql
/**
 * Created by Eric on 16.11.2014.
 */

import scala.reflect.ClassTag

/**
 * This trait allows to easily write simple traversals.
 * Instead of writing:
 *  t : T
 *  val x = down(collect{...})
 *  val result = x(t).result
 * We can wirte instead
 *  t.collect{...}.result
 *
 *  downBreak(filter{..} ~> down{update{...}}) (t)
 *  becomes
 *  t.downBreak.filter{..}.down.update{..}
 *  Which is essentially easier to read and to write (no parenthesis)
 *  The drawbacks:
 *    - Every combinator have to be re-written in those 'Lazy evaluator' classes (even macros)
 *    - Composition is lost.
 *
 *  Of course everything is computed lazily (like Collection View in Scala) so the resulte have to be forced.
 * */
trait LazyEvaluator[T] { self: Combinators[T] with Traverser[T] with SyntaxEnhancer[T] =>

  /**Abstract class used to delay delay the time when the type parameter of
    * a meta combinator is decided*/
  abstract class DelayedMeta{
    def apply[A : Monoid](m: Matcher[A]): Matcher[A]
  }

  /**
   * Allows to call 'combinators' directly on T
   * */
  implicit class LazyEvaluator(t: T){

    def collect[A](f: PartialFunction[T, A])(implicit x: ClassTag[T], y : ClassTag[A]) =
      down.collect(f)

    def guard[U <: T : ClassTag](f: PartialFunction[U, Boolean]) =
      down.guard(f)

    def transform[I <: T : ClassTag, O <: T](f: PartialFunction[I, O])(implicit x: AllowedTransformation[I, O]) =
      down.transform(f)

    def down      = new LazyEvaluatorMeta(t, new DelayedMeta{def apply[A : Monoid](x: Matcher[A]) = self.down(x)})
    def downBreak = new LazyEvaluatorMeta(t, new DelayedMeta{def apply[A : Monoid](x: Matcher[A]) = self.downBreak(x)})
    def up        = new LazyEvaluatorMeta(t, new DelayedMeta{def apply[A : Monoid](x: Matcher[A]) = self.up(x)})
    def upBreak   = new LazyEvaluatorMeta(t, new DelayedMeta{def apply[A : Monoid](x: Matcher[A]) = self.upBreak(x)})
    def children  = new LazyEvaluatorMeta(t, new DelayedMeta{def apply[A : Monoid](x: Matcher[A]) = self.children(x)})
  }



  class LazyEvaluatorMeta(t: T, meta: DelayedMeta){

    def collect[A](f: PartialFunction[T, A])(implicit x: ClassTag[T], y : ClassTag[A]) =
      new LazyEvaluatorAndThen[List[A]](t, self.collect[A](f), meta)

    def guard[U <: T : ClassTag](f: PartialFunction[U, Boolean]) =
      new LazyEvaluatorAndThen(t, self.guard(f), meta)

    def transform[I <: T : ClassTag, O <: T](f: PartialFunction[I, O])(implicit x: AllowedTransformation[I, O]) =
      new LazyEvaluatorAndThen(t, self.transform(f), meta)

  }


  class LazyEvaluatorAndThen[A](t: T, m: Matcher[A], meta: DelayedMeta){

    def collect[B](f: PartialFunction[T, B])(implicit x: ClassTag[T], y : ClassTag[A], z : ClassTag[B]) =
      new LazyEvaluatorAndThen(t, m ~> self.collect(f), meta)

    def guard[U <: T : ClassTag](f: PartialFunction[U, Boolean]) =
      new LazyEvaluatorAndThen(t, m ~> self.guard(f), meta)

    def transform[I <: T : ClassTag, O <: T](f: PartialFunction[I, O])(implicit x: AllowedTransformation[I, O]) =
      new LazyEvaluatorAndThen(t, m ~> self.transform(f), meta)

    def down =
      new LazyEvaluatorMeta(t, new DelayedMeta{def apply[A : Monoid](x: Matcher[A]) = meta(m ~> self.down(x))})
    def downBreak =
      new LazyEvaluatorMeta(t, new DelayedMeta{def apply[A : Monoid](x: Matcher[A]) = meta(m ~> self.downBreak(x))})
    def up =
      new LazyEvaluatorMeta(t, new DelayedMeta{def apply[A : Monoid](x: Matcher[A]) = meta(m ~> self.up(x))})
    def upBreak =
      new LazyEvaluatorMeta(t, new DelayedMeta{def apply[A : Monoid](x: Matcher[A]) = meta(m ~> self.upBreak(x))})


    def force(implicit x: Monoid[A]) = meta(m).apply(t)
    def result(implicit x: Monoid[A]) = force.result
    def tree(implicit x: Monoid[A]) = force.tree
  }


}
