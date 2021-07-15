// SPDX-License-Identifier: Apache-2.0

package chisel3.experimental

import chisel3._
import chisel3.internal.{AggregateViewBinding, TopBinding, ViewBinding, requireIsChiselType}

import scala.annotation.tailrec
import scala.collection.mutable

package object dataview {
  case class InvalidViewException(message: String) extends ChiselException(message)

  private def nonTotalViewException(dataView: DataView[_, _], target: Any, view: Data, targetFields: Seq[String], viewFields: Seq[String]) = {
    def missingMsg(name: String, fields: Seq[String]): Option[String] = {
      val str = fields.mkString(", ")
      fields.size match {
        case 0 => None
        case 1 => Some(s"$name field '$str' is missing")
        case _ => Some(s"$name fields '$str' are missing")
      }
    }
    val vs = missingMsg("view", viewFields)
    val ts = missingMsg("target", targetFields)
    val reasons = (ts ++ vs).mkString(" and ").capitalize
    val suggestion = if (ts.nonEmpty) "\n  If the view *should* be non-total, try a 'PartialDataView'." else ""
    val msg = s"Viewing $target as $view is non-Total!\n  $reasons.\n  DataView used is $dataView.$suggestion"
    throw InvalidViewException(msg)
  }

  // Safe for unbound
  private def isView(d: Data): Boolean = d.topBindingOpt.exists {
    case (_: ViewBinding | _: AggregateViewBinding) => true
    case _ => false
  }

  // TODO should this be moved to class Aggregate / can it be unified with Aggregate.bind?
  private def doBind[T : DataProduct, V <: Data](target: T, view: V, dataView: DataView[T, V]): Unit = {
    val mapping = dataView.mapping(target, view)
    val total = dataView.total
    // Lookups to check the mapping results
    val viewFieldLookup: Map[Data, String] = getRecursiveFields(view, "_").toMap
    val targetContains: Data => Boolean = implicitly[DataProduct[T]].dataSet(target)

    // Resulting bindings for each Element of the View
    val childBindings =
      new mutable.HashMap[Data, mutable.ListBuffer[Element]] ++
        viewFieldLookup.view
          .collect { case (elt: Element, _) => elt }
          .map(_ -> new mutable.ListBuffer[Element])

    for ((ax, bx) <- mapping) {
      def err(name: String, arg: Data) =
        throw new Exception(s"View mapping must only contain Elements within the $name, got $arg")
      val fieldName = viewFieldLookup.getOrElse(bx, err("View", bx))
      if (!targetContains(ax)){ err("Target", ax) }

      bx match {
        // Special cased because getMatchedFields checks typeEquivalence on Elements (and is used in Aggregate path)
        // Also saves object allocations on common case of Elements
        case elt: Element =>
          if (elt.getClass != ax.getClass) {  // TODO typeEquivalent is too strict because it checks width
            throw new Exception(s"Field $fieldName $elt specified as view of non-type-equivalent value $ax")
          }
          childBindings(elt) += ax.asInstanceOf[Element]

        case agg: Aggregate =>
          if (!agg.typeEquivalent(ax)) {
            throw new Exception(s"field $fieldName $agg specified with non-type-equivalent value $ax")
          }
          getMatchedFields(agg, ax).foreach {
            case (belt: Element, aelt: Element) =>
              childBindings(belt) += aelt
            case _ => // Ignore matching of Aggregates
          }
      }
    }

    // Errors in totality of the View, use var List to keep fast path cheap (no allocation)
    var viewNonTotalErrors: List[Data] = Nil
    var targetNonTotalErrors: List[String] = Nil

    val targetSeen: Option[mutable.Set[Data]] = if (total) Some(mutable.Set.empty[Data]) else None

    val resultBindings = childBindings.map { case (data, targets) =>
      val targetsx = targets match {
        case collection.Seq(target: Element) => target
        case collection.Seq() =>
          viewNonTotalErrors = data :: viewNonTotalErrors
          data.asInstanceOf[Element] // Return the Data itself, will error after this map, cast is safe
        case x =>
          throw new Exception(s"Got $x, expected Seq(_: Direct)")
      }
      // TODO record and report aliasing errors
      targetSeen.foreach(_ += targetsx)
      data -> targetsx
    }.toMap

    // Check for totality of Target
    targetSeen.foreach { seen =>
      val lookup = implicitly[DataProduct[T]].dataIterator(target, "_")
      for (missed <- lookup.collect { case (d: Element, name) if !seen(d) => name }) {
        targetNonTotalErrors = missed :: targetNonTotalErrors
      }
    }
    if (viewNonTotalErrors != Nil || targetNonTotalErrors != Nil) {
      val viewErrors = viewNonTotalErrors.map(f => viewFieldLookup.getOrElse(f, f.toString))
      nonTotalViewException(dataView, target, view, targetNonTotalErrors, viewErrors)
    }

    view.bind(AggregateViewBinding(resultBindings))
  }

  private def bindElt[A : DataProduct, B <: Element](a: A, b: B, mapping: Iterable[(Data, Data)]): Unit = {
    mapping.toList match {
      case (ax, `b`) :: Nil =>
        // TODO Check that ax and b have the same type
        b.bind(ViewBinding(ax.asInstanceOf[Element]))
      case other => throw new Exception(s"Expected exactly 1 mapping, got $other")
    }
  }

  // TODO is this right place to put this?
  /** Provides `viewAs` for types that are supported as [[DataView]] targets */
  implicit class DataViewable[T : DataProduct](target: T) {
    def viewAs[V <: Data](view: V)(implicit dataView: DataView[T, V]): V = {
      requireIsChiselType(view, "viewAs")
      val result: V = view.cloneTypeFull

      doBind(target, result, dataView)
      result
    }
  }

  /** Similar to [[reify]] but can be used on unbound and non-Element arguments
    */
  private def reifyOpt(data: Data): Data = data.topBindingOpt match {
    case None => data
    case Some(ViewBinding(target)) => reifyOpt(target)
    case Some(_) => data
  }

  /** Turn any [[Element]] that could be a View into a concrete Element
    *
    * This is the fundamental "unwrapping" or "tracing" primitive operation for handling Views within
    * Chisel.
    */
  private[chisel3] def reify(elt: Element): Element =
    reify(elt, elt.topBinding)

  /** Turn any [[Element]] that could be a View into a concrete Element
    *
    * This is the fundamental "unwrapping" or "tracing" primitive operation for handling Views within
    * Chisel.
    */
  @tailrec private[chisel3] def reify(elt: Element, topBinding: TopBinding): Element =
    topBinding match {
      case ViewBinding(target) => reify(target, elt.topBinding)
      case _ => elt
    }
}
