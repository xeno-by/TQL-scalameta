package tqlscalameta

/**
 * Created by Eric on 20.10.2014.
 */

import ScalaMetaTraverser._
import scala.meta._
import scala.meta.syntactic.ast._

object Example extends App{


  val x =
    q"""
       if (3 == 17) 3
       else 2
       5
       """

  val getMin = down(stateful(Int.MaxValue){state =>
    visit{case Lit.Int(a) => (List(() => state), Math.min(state,a))}
  })

  val getAvg = down(stateful((0, 0)){state => {
      lazy val avg = state._1 / state._2
      visit{case Lit.Int(a) => (List(() => avg), (state._1 + a, state._2 + 1))}
    }
  })

  val getAllInts = down(collectIn[Set]{case Lit.Int(a) => a})

  println(getAvg(x).result.map(_()))
  println(getAllInts(x).result)
}
