// SPDX-License-Identifier: Apache-2.0

package chisel3.experimental.dataview

import chisel3._
import chisel3.internal.sourceinfo.SourceInfo
import scala.reflect.runtime.universe.WeakTypeTag

import annotation.implicitNotFound

// TODO can this be a trait?
// TODO what about views as the same type? What happens if the width or parameters are different?

@implicitNotFound("Could not find implicit value for DataView[${T}, ${V}].\n" +
  "Please see https://www.chisel-lang.org/chisel3/docs/explanations/dataview#dataproduct")
sealed class DataView[T : DataProduct, V <: Data] private[chisel3] (
  private[chisel3] val mapping: (T, V) => Iterable[(Data, Data)],
  val total: Boolean
)(
  implicit private[chisel3] val sourceInfo: SourceInfo
) {
  override def toString: String = {
    val base = sourceInfo.makeMessage(x => x)
    val loc = if (base.nonEmpty) base else "@unknown"
    val name = if (total) "DataView" else "PartialDataView"
    s"$name(defined $loc)"
  }

  /** Compose two `DataViews` together to construct a view from the target of this `DataView` to the
    * view type of the second `DataView`
    *
    * @note Chisel3 uses objects to represent Chisel types. We need a way to derive an object of the
    *       intermediate type `B` from objects of the given types `A` and `C`.
    * @param g a DataView from `V` to new view-type `V2`
    * @param gen Function to generate an intermediate "Chisel type" object
    * @tparam V2 View type of `DataView` `g`
    * @return a new `DataView` from the original `T` to new view-type `V2`
    */
  def andThen[V2 <: Data](g: DataView[V, V2])(gen: (T, V2) => V)(implicit sourceInfo: SourceInfo): DataView[T, V2] = {
    val view2 = g.invert
    new DataView[T, V2](
      { case (a, c) =>
          val b = gen(a, c)
          List(a.viewAs(b)(this) -> c.viewAs(b)(view2))
      },
      this.total && view2.total
    )
  }
}
/** Implementation of viewing a Record as its parent type
  *
  * This does a Stringly-typed mapping which is safe because we have a direct inheritance relationship
  */
private class RecordAsParentView[T <: Record, V <: Record](implicit ev: T <:< V, sourceInfo: SourceInfo) extends DataView[T, V](
  { case (a, b) =>
    val aElts = a.elements
    val bElts = b.elements
    val bKeys = bElts.keySet
    val keys = aElts.keysIterator.filter(bKeys.contains)
    keys.map(k => aElts(k) -> bElts(k)).toSeq
  },
  // TODO can we make this total when T =:= V?
  total = false
)

/** Factory methods for constructing [[DataView]]s
  *
  */
object DataView {

  def apply[T : DataProduct, V <: Data](pairs: ((T, V) => (Data, Data))*)(implicit sourceInfo: SourceInfo): DataView[T, V] =
    DataView.pairs(pairs: _*)

  def pairs[T : DataProduct, V <: Data](pairs: ((T, V) => (Data, Data))*)(implicit sourceInfo: SourceInfo): DataView[T, V] =
    mapping(swizzle(pairs))

  def mapping[T : DataProduct, V <: Data](mapping: (T, V) => Iterable[(Data, Data)])(implicit sourceInfo: SourceInfo): DataView[T, V] =
    new DataView[T, V](mapping, total = true)

  /** Provides `invert` for invertible [[DataView]]s
    *
    * This must be done as an extension method because it applies an addition constraint on the `Target`
    * type parameter, namely that it must be a subtype of [[Data]]
    */
  implicit class InvertibleDataView[T <: Data : WeakTypeTag, V <: Data : WeakTypeTag](view: DataView[T, V]) {
    def invert: DataView[V, T] = {
      // It would've been nice to make this a compiler error, but it's unclear how to make that work.
      // We tried having separate TotalDataView and PartialDataView and only defining inversion for
      // TotalDataView. For some reason, implicit resolution wouldn't invert TotalDataViews. This is
      // probably because it was looking for the super-type DataView and since invertDataView was
      // only defined on TotalDataView, it wasn't included in implicit resolution. Thus we end up
      // with a runtime check.
      if (!view.total) {
        val tt = implicitly[WeakTypeTag[T]].tpe
        val vv = implicitly[WeakTypeTag[V]].tpe
        val msg = s"Cannot invert '$view' as it is non-total.\n  Try providing a DataView[$vv, $tt]." +
          s"\n  Please see https://www.chisel-lang.org/chisel3/docs/explanations/dataview."
        throw InvalidViewException(msg)
      }
      implicit val sourceInfo = view.sourceInfo
      new DataView[V, T](swapArgs(view.mapping), view.total)
    }
  }

  private[dataview] def swizzle[A, B, C, D](fs: Iterable[(A, B) => (C, D)]): (A, B) => Iterable[(C, D)] = {
    case (a, b) => fs.map(f => f(a, b))
  }

  private def swapArgs[A, B, C, D](f: (A, B) => Iterable[(C, D)]): (B, A) => Iterable[(D, C)] = {
    case (b, a) => f(a, b).map(_.swap)
  }

  // Rob Norris (tpolecat) advises against this: https://gitter.im/scala/scala?at=6080bd5881866c680c3ac477
  //   It leads to "diverging implicit expansion" errors if an implicit isn't found (which is a terrible error message)
  // We tried having the DataView factory methods return tuples, but this lead to terrible error messages:
  //   https://gitter.im/scala/scala?at=60ad912f9d18fe1998270cd8
  //   (eg. "recursive value x$1 needs type" when no value named "x" even exists in the code)
  // Instead, we keep implicit swapping but provide a low-priority default DataView that is just a macro
  //   expanding into a custom error message, see: dataViewNotFound
  /** Total [[DataView]]s are bidirectional mappings so need only be implemented in one direction */
  implicit def swapDataView[A <: Data : WeakTypeTag, B <: Data : WeakTypeTag](implicit d: DataView[B, A]): DataView[A, B] =
    d.invert


  /** All Chisel Data are viewable as their own type */
  def identityView[A <: Data](implicit sourceInfo: SourceInfo): DataView[A, A] = DataView[A, A]({ case (x, y) => (x, y) })

  // Due to ambiguity with asParentRecord for Records, we provide specific identities for other types
  // and use asParentRecord as the identity for Records

  implicit def elementIdentityView[A <: Element](implicit sourceInfo: SourceInfo): DataView[A, A] = identityView[A]
  implicit def vecIdentityView[A <: Vec[_]](implicit sourceInfo: SourceInfo): DataView[A, A] = identityView[A]

  // Should this be part of default import or not, would simplify the identity stuff above,
  // Another option is to use low-priority implicits (aka macros) for identityView
  implicit def asParentRecord[T <: Record, V <: Record](implicit ev: T <:< V, sourceInfo: SourceInfo): DataView[T, V] =
    new RecordAsParentView[T, V]

  import scala.language.experimental.macros
  import scala.reflect.macros.blackbox.Context

  /** Default implementation of [[DataView]] that causes a compiler error
    *
    * This is sort of a DIY @implicitNotFound except it also prevents "diverging implicit expansion"
    * errors that would occur due to [[swapDataView]] causing infinite recursion in implicit resolution.
    */
  implicit def dataViewNotFound[T, V <: Data]: DataView[T, V] = macro dataViewNotFound_impl[T, V]

  def dataViewNotFound_impl[T, V](c: Context)(implicit t: c.WeakTypeTag[T], v: c.WeakTypeTag[V]): c.Tree = {
    val msg = s"Could not find implicit value for DataView[${t.tpe}, ${v.tpe}].\n" +
      "Please see https://www.chisel-lang.org/chisel3/docs/explanations/dataview#dataproduct."
    c.abort(c.enclosingPosition, msg)
  }
}

/** Factory methods for constructing non-total [[DataView]]s
  *
  */
object PartialDataView {

  def apply[T: DataProduct, V <: Data](pairs: ((T, V) => (Data, Data))*)(implicit sourceInfo: SourceInfo): DataView[T, V] =
    PartialDataView.pairs(pairs: _*)

  def pairs[T: DataProduct, V <: Data](pairs: ((T, V) => (Data, Data))*)(implicit sourceInfo: SourceInfo): DataView[T, V] =
    mapping(DataView.swizzle(pairs))

  def mapping[T: DataProduct, V <: Data](mapping: (T, V) => Iterable[(Data, Data)])(implicit sourceInfo: SourceInfo): DataView[T, V] =
    new DataView[T, V](mapping, total = false)
}
