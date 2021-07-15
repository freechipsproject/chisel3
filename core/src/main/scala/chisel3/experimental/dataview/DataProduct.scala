// SPDX-License-Identifier: Apache-2.0

package chisel3.experimental.dataview

import chisel3.{Data, RawModule, getRecursiveFields}

import scala.annotation.implicitNotFound

/** Typeclass interface for getting elements of type [[Data]]
  *
  * This is needed for validating [[DataView]]s targeting type `A`.
  * Can be thought of as "can be the Target of a DataView".
  * @tparam A Type that has elements of type [[Data]]
  */
@implicitNotFound("Could not find implicit value for DataProduct[${A}].\n" +
  "Please see https://www.chisel-lang.org/chisel3/docs/explanations/dataview#dataproduct")
trait DataProduct[-A] {
  /** Provides [[Data]] elements within some containing object
    *
    * @param a Containing object
    * @param path Hierarchical path to current signal (for error reporting)
    * @return Data elements and associated String paths (Strings for error reporting only!)
    */
  def dataIterator(a: A, path: String): Iterator[(Data, String)]

  /** Returns a checker to test if the containing object contains a `Data` object
    * @note Implementers may want to override if iterating on all `Data` is expensive for `A` and `A`
    *       will primarily be used in `PartialDataViews`
    * @note The returned value is a function, not a true Set, but is describing the functionality of
    *       Set containment
    * @param a Containing object
    * @return A checker that itself returns True if a given `Data` is contained in `a`
    *         as determined by an `==` test
    */
  def dataSet(a: A): Data => Boolean = dataIterator(a, "").map(_._1).toSet
}

object DataProduct {
  implicit val dataDataProduct: DataProduct[Data] = new DataProduct[Data] {
    def dataIterator(a: Data, path: String): Iterator[(Data, String)] =
      getRecursiveFields(a, path).iterator
  }

  implicit val userModuleDataProduct: DataProduct[RawModule] = new DataProduct[RawModule] {
    def dataIterator(a: RawModule, path: String): Iterator[(Data, String)] = ???
    // Overridden for performance
    override def dataSet(a: RawModule): Data => Boolean = { e =>
      e._id > a._id && e._id <= a._lastId
    }
  }
}