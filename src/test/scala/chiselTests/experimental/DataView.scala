// See LICENSE for license details.

package chiselTests.experimental

import chiselTests.ChiselFlatSpec
import chisel3._
import chisel3.experimental.dataview._
import chisel3.stage.ChiselStage
import chisel3.util.{Decoupled, DecoupledIO}

object SimpleBundleDataView {
  class BundleA extends Bundle {
    val foo = UInt(8.W)
  }
  class BundleB extends Bundle {
    val bar = UInt(8.W)
  }
  implicit val view = DataView[BundleA, BundleB](_.foo -> _.bar)
}

object VecBundleDataView {
  class MyBundle extends Bundle {
    val foo = UInt(8.W)
    val bar = UInt(8.W)
  }
  implicit val view: DataView[MyBundle, Vec[UInt]] = DataView(_.foo -> _(1), _.bar -> _(0))
}

// This should become part of Chisel in a later PR
object ProductDataProduct {
  implicit val productDataProduct: DataProduct[Product] = new DataProduct[Product] {
    def dataIterator(a: Product, path: String): Iterator[(Data, String)] = {
      a.productIterator.zipWithIndex.collect { case (d: Data, i) => d -> s"$path._${i + 1}" }
    }
  }
}

// This should become part of Chisel in a later PR
object HWTuple {
  import ProductDataProduct._

  class HWTuple2[A <: Data, B <: Data](val _1: A, val _2: B) extends Bundle

  // Provide mapping from Tuple2 to HWTuple2
  implicit def view[A <: Data, B <: Data]: DataView[(A, B), HWTuple2[A, B]] =
    DataView(_._1 -> _._1, _._2 -> _._2)

  // Implicit conversion to make the code pretty
  implicit def tuple2hwtuple[A <: Data, B <: Data](tup: (A, B)): HWTuple2[A, B] =
    tup.viewAs(new HWTuple2(tup._1.cloneType, tup._2.cloneType))
}

// This should become part of Chisel in a later PR
object SeqDataProduct {
  // Should we special case Seq[Data]?
  implicit def seqDataProduct[A : DataProduct]: DataProduct[Seq[A]] = new DataProduct[Seq[A]] {
    def dataIterator(a: Seq[A], path: String): Iterator[(Data, String)] = {
      val dpa = implicitly[DataProduct[A]]
      a.iterator
        .zipWithIndex
        .flatMap { case (elt, idx) =>
          dpa.dataIterator(elt, s"$path[$idx]")
        }
    }
  }
}

class DataViewSpec extends ChiselFlatSpec {

  behavior of "DataView"

  it should "support simple Bundle viewing" in {
    import SimpleBundleDataView._
    class MyModule extends Module {
      val in = IO(Input(new BundleA))
      val out = IO(Output(new BundleB))
      out := in.viewAs(new BundleB)
    }
    val chirrtl = ChiselStage.emitChirrtl(new MyModule)
    chirrtl should include("out.bar <= in.foo")
  }

  it should "be a bidirectional mapping" in {
    import SimpleBundleDataView._
    class MyModule extends Module {
      val in = IO(Input(new BundleA))
      val out = IO(Output(new BundleB))
      out.viewAs(new BundleA) := in
    }
    val chirrtl = ChiselStage.emitChirrtl(new MyModule)
    chirrtl should include("out.bar <= in.foo")
  }

  it should "handle viewing UInts as UInts" in {
    class MyModule extends Module {
      val in = IO(Input(UInt(8.W)))
      val foo = IO(Output(UInt(8.W)))
      val bar = IO(Output(UInt(8.W)))
      foo := in.viewAs(UInt(8.W))
      bar.viewAs(UInt(8.W)) := in
    }
    val chirrtl = ChiselStage.emitChirrtl(new MyModule)
    chirrtl should include("foo <= in")
    chirrtl should include("bar <= in")
  }

  it should "handle viewing Bundles as their same concrete type" in {
    class MyBundle extends Bundle {
      val foo = UInt(8.W)
    }
    class MyModule extends Module {
      val in = IO(Input(new MyBundle))
      val fizz = IO(Output(new MyBundle))
      val buzz = IO(Output(new MyBundle))
      fizz := in.viewAs(new MyBundle)
      buzz.viewAs(new MyBundle) := in
    }
    val chirrtl = ChiselStage.emitChirrtl(new MyModule)
    chirrtl should include("fizz.foo <= in.foo")
    chirrtl should include("buzz.foo <= in.foo")
  }

  it should "handle viewing Vecs as their same concrete type" in {
    class MyModule extends Module {
      val in = IO(Input(Vec(1, UInt(8.W))))
      val fizz = IO(Output(Vec(1, UInt(8.W))))
      val buzz = IO(Output(Vec(1, UInt(8.W))))
      fizz := in.viewAs(Vec(1, UInt(8.W)))
      buzz.viewAs(Vec(1, UInt(8.W))) := in
    }
    val chirrtl = ChiselStage.emitChirrtl(new MyModule)
    chirrtl should include("fizz[0] <= in[0]")
    chirrtl should include("buzz[0] <= in[0]")
  }

  it should "handle viewing Vecs as Bundles and vice versa" in {
    import VecBundleDataView._
    class MyModule extends Module {
      val in = IO(Input(new MyBundle))
      val out = IO(Output(Vec(2, UInt(8.W))))
      val out2 = IO(Output(Vec(2, UInt(8.W))))
      out := in.viewAs(Vec(2, UInt(8.W)))
      out2.viewAs(new MyBundle) := in
    }
    val chirrtl = ChiselStage.emitChirrtl(new MyModule)
    chirrtl should include("out[0] <= in.bar")
    chirrtl should include("out[1] <= in.foo")
    chirrtl should include("out2[0] <= in.bar")
    chirrtl should include("out2[1] <= in.foo")
  }

  it should "work with bidirectional connections for nested types" in {
    class FizzBuzz extends Bundle {
      val fizz = UInt(8.W)
      val buzz = UInt(8.W)
    }
    class FlatDecoupled extends Bundle {
      val valid = Output(Bool())
      val ready = Input(Bool())
      val fizz = Output(UInt(8.W))
      val buzz = Output(UInt(8.W))
    }
    implicit val view = DataView[FlatDecoupled, DecoupledIO[FizzBuzz]](
      _.valid -> _.valid,
      _.ready -> _.ready,
      _.fizz -> _.bits.fizz,
      _.buzz -> _.bits.buzz
    )
    class MyModule extends Module {
      val enq = IO(Flipped(Decoupled(new FizzBuzz)))
      val deq = IO(new FlatDecoupled)
      val deq2 = IO(new FlatDecoupled)
      deq <> enq.viewAs(new FlatDecoupled)
      deq2.viewAs(Decoupled(new FizzBuzz)) <> enq
    }
    val chirrtl = ChiselStage.emitChirrtl(new MyModule)
    chirrtl should include("deq.valid <= enq.valid")
    chirrtl should include("enq.ready <= deq.ready")
    chirrtl should include("deq.fizz <= enq.bits.fizz")
    chirrtl should include("deq.buzz <= enq.bits.buzz")
    chirrtl should include("deq2.valid <= enq.valid")
    chirrtl should include("enq.ready <= deq2.ready")
    chirrtl should include("deq2.fizz <= enq.bits.fizz")
    chirrtl should include("deq2.buzz <= enq.bits.buzz")
  }

  it should "support viewing a Bundle as a Parent Bundle type" in {
    class Foo extends Bundle {
      val foo = UInt(8.W)
    }
    class Bar extends Foo {
      val bar = UInt(8.W)
    }
    class MyModule extends Module {
      val fooIn = IO(Input(new Foo))
      val barOut = IO(Output(new Bar))
      barOut.viewAs(new Foo) := fooIn

      val barIn = IO(Input(new Bar))
      val fooOut = IO(Output(new Foo))
      fooOut := barIn.viewAs(new Foo)
    }
    val chirrtl = ChiselStage.emitChirrtl(new MyModule)
    chirrtl should include("barOut.foo <= fooIn.foo")
    chirrtl should include("fooOut.foo <= barIn.foo")
  }

  it should "error if viewing a parent Bundle as a child Bundle type" in {
    class Foo extends Bundle {
      val foo = UInt(8.W)
    }
    class Bar extends Foo {
      val bar = UInt(8.W)
    }
    class MyModule extends Module {
      val barIn = IO(Input(new Bar))
      val fooOut = IO(Output(new Foo))
      fooOut.viewAs(new Bar) := barIn
    }
    an [InvalidViewException] shouldBe thrownBy {
      ChiselStage.emitChirrtl(new MyModule)
    }

  }

  it should "work in UInt operations" in {
    class MyModule extends Module {
      val a = IO(Input(UInt(8.W)))
      val b = IO(Input(new Bundle {
        val value = UInt(8.W)
      }))
      val cond = IO(Input(Bool()))
      val and, mux, bitsCat = IO(Output(UInt(8.W)))
      // Chisel unconditionally emits a node, so name it at least
      val x = a.viewAs(UInt(8.W)) & b.value.viewAs(UInt(8.W))
      and.viewAs(UInt(8.W)) := x

      val y = Mux(cond.viewAs(Bool()), a.viewAs(UInt(8.W)), b.value.viewAs(UInt(8.W)))
      mux.viewAs(UInt(8.W)) := y

      // TODO should we have a macro so that we don't need .apply?
      val aBits = a.viewAs(UInt(8.W)).apply(3, 0)
      val bBits = b.value.viewAs(UInt(8.W)).apply(3, 0)
      val abCat = aBits.viewAs(UInt(4.W)) ## bBits.viewAs(UInt(4.W))
      bitsCat := abCat
    }
    val chirrtl = ChiselStage.emitChirrtl(new MyModule)
    val expected = List(
      "node x = and(a, b.value)",
      "and <= x",
      "node y = mux(cond, a, b.value)",
      "mux <= y",
      "node aBits = bits(a, 3, 0)",
      "node bBits = bits(b.value, 3, 0)",
      "node abCat = cat(aBits, bBits)",
      "bitsCat <= abCat"
    )
    for (line <- expected) {
      chirrtl should include(line)
    }
  }

  it should "support .asUInt of Views" in {
    import VecBundleDataView._
    class MyModule extends Module {
      val barIn = IO(Input(new MyBundle))
      val fooOut = IO(Output(UInt()))
      val cat = barIn.viewAs(Vec(2, UInt(8.W))).asUInt
      fooOut := cat
    }
    val chirrtl = ChiselStage.emitChirrtl(new MyModule)
    chirrtl should include ("node cat = cat(barIn.foo, barIn.bar)")
    chirrtl should include ("fooOut <= cat")
  }

  it should "be composable" in {
    // Given DataView[A, B] and DataView[B, C], derive DataView[A, C]
    class Foo(val foo: UInt) extends Bundle
    class Bar(val bar: UInt) extends Bundle
    class Fizz(val fizz: UInt) extends Bundle

    implicit val foo2bar: DataView[Foo, Bar] = DataView(_.foo -> _.bar)
    implicit val bar2fizz: DataView[Bar, Fizz] = DataView(_.bar -> _.fizz)

    implicit val foo2fizz: DataView[Foo, Fizz] = foo2bar.andThen(bar2fizz) {
      case (foo, _) => new Bar(foo.foo.cloneType)
    }

    class MyModule extends Module {
      val a, b = IO(Input(new Foo(UInt(8.W))))
      val y, z = IO(Output(new Fizz(UInt(8.W))))
      y := a.viewAs(chiselTypeOf(y))
      z := a.viewAs(new Bar(UInt(8.W))).viewAs(chiselTypeOf(z))
    }
    val chirrtl = ChiselStage.emitChirrtl(new MyModule)
    println(chirrtl)
  }

  // This example should be turned into a built-in feature
  it should "enable implementing \"HardwareTuple\"" in {
    import HWTuple._

    class MyModule extends Module {
      val a, b, c, d = IO(Input(UInt(8.W)))
      val sel = IO(Input(Bool()))
      val y, z = IO(Output(UInt(8.W)))
      (y, z) := Mux(sel, (a, b), (c, d))
    }
    // Verilog instead of CHIRRTL because the optimizations make it much prettier
    val verilog = ChiselStage.emitVerilog(new MyModule)
    verilog should include ("assign y = sel ? a : c;")
    verilog should include ("assign z = sel ? b : d;")
  }

  // This example should be turned into a built-in feature
  it should "enable viewing Seqs as Vecs" in {
    import SeqDataProduct._

    implicit def view[A <: Data]: DataView[Seq[A], Vec[A]] =
      DataView.mapping { case (s: Seq[A], v: Vec[A]) => s.zip(v) }

    class MyModule extends Module {
      val a, b, c = IO(Input(UInt(8.W)))
      val x, y, z = IO(Output(UInt(8.W)))
      val asSeq = Seq(x, y, z).viewAs(Vec(3, UInt()))
      asSeq := VecInit(a, b, c)
    }
    // Verilog instead of CHIRRTL because the optimizations make it much prettier
    val verilog = ChiselStage.emitVerilog(new MyModule)
    verilog should include ("assign x = a;")
    verilog should include ("assign y = b;")
    verilog should include ("assign z = c;")
  }

  ignore should "support composition of views" in {
    import ProductDataProduct._
    import SeqDataProduct._
    import HWTuple._

    implicit def view[A : DataProduct, B <: Data](implicit dv: DataView[A, B]): DataView[Seq[A], Vec[B]] =
      DataView.mapping { case (s, v) =>
        s.zip(v).map { case (a, b) => a.viewAs(b).asInstanceOf[B] -> b }
      }

    class MyModule extends Module {
      val a, b, c, d = IO(Output(UInt(8.W)))

      val v = Seq((a, b), (c, d)).viewAs(Vec(2, new HWTuple2(UInt(), UInt())))
      // A little annoying that we need the type annotation to get the implicit conversion to work
      v := VecInit[HWTuple2[UInt, UInt]]((0.U, 1.U), (2.U, 3.U))
    }
    val verilog = ChiselStage.emitVerilog(new MyModule)
  }

  it should "error if the mapping is non-total in the view" in {
    class MyBundle(val foo: UInt, val bar: UInt) extends Bundle
    implicit val dv = DataView[UInt, MyBundle](_ -> _.bar)
    class MyModule extends Module {
      val tpe = new MyBundle(UInt(8.W), UInt(8.W))
      val in = IO(Input(UInt(8.W)))
      val out = IO(Output(tpe))
      out := in.viewAs(tpe)
    }
    val err = the [InvalidViewException] thrownBy (ChiselStage.emitVerilog(new MyModule))
    err.toString should include ("View field '_.foo' is missing")
  }

  it should "error if the mapping is non-total in the target" in {
    import ProductDataProduct._
    implicit val dv = DataView[(UInt, UInt), UInt](_._1 -> _)
    class MyModule extends Module {
      val a, b = IO(Input(UInt(8.W)))
      val out = IO(Output(UInt(8.W)))
      out := (a, b).viewAs(UInt())
    }
    val err = the [InvalidViewException] thrownBy (ChiselStage.emitVerilog(new MyModule))
    err.toString should include ("Target field '_._2' is missing")
  }

  it should "error if the mapping contains Data that are not part of the Target" in {
    class BundleA extends Bundle {
      val foo = UInt(8.W)
    }
    class BundleB extends Bundle {
      val fizz = UInt(8.W)
      val buzz = UInt(8.W)
    }
    implicit val dv = DataView[BundleA, BundleB](_.foo -> _.fizz, (_, b) => (3.U, b.buzz))
    class MyModule extends Module {
      val in = IO(Input(new BundleA))
      val out = IO(Output(new BundleB))
      out := in.viewAs(new BundleB)
    }
    // TODO more precise Exception type
    val err = the [Exception] thrownBy (ChiselStage.emitVerilog(new MyModule))
    err.toString should include ("View mapping must only contain Elements within the Target")
  }

  it should "error if the mapping contains Data that are not part of the View" in {
    class BundleA extends Bundle {
      val foo = UInt(8.W)
    }
    class BundleB extends Bundle {
      val fizz = UInt(8.W)
      val buzz = UInt(8.W)
    }
    implicit val dv = DataView[BundleA, BundleB](_.foo -> _.fizz, (_, b) => (3.U, b.buzz))
    class MyModule extends Module {
      val in = IO(Input(new BundleA))
      val out = IO(Output(new BundleB))
      out.viewAs(new BundleA) := in
    }
    // TODO more precise Exception type
    val err = the [Exception] thrownBy (ChiselStage.emitVerilog(new MyModule))
    err.toString should include ("View mapping must only contain Elements within the View")
  }

  behavior of "PartialDataView"

  it should "still error if the mapping is non-total in the view" in {
    class MyBundle(val foo: UInt, val bar: UInt) extends Bundle
    implicit val dv = PartialDataView[UInt, MyBundle](_ -> _.bar)
    class MyModule extends Module {
      val tpe = new MyBundle(UInt(8.W), UInt(8.W))
      val in = IO(Input(UInt(8.W)))
      val out = IO(Output(tpe))
      out := in.viewAs(tpe)
    }
    val err = the [InvalidViewException] thrownBy (ChiselStage.emitVerilog(new MyModule))
    err.toString should include ("View field '_.foo' is missing")
  }

  it should "NOT error if the mapping is non-total in the target" in {
    import ProductDataProduct._
    implicit val dv = PartialDataView[(UInt, UInt), UInt](_._2 -> _)
    class MyModule extends Module {
      val a, b = IO(Input(UInt(8.W)))
      val out = IO(Output(UInt(8.W)))
      out := (a, b).viewAs(UInt())
    }
    val verilog = ChiselStage.emitVerilog(new MyModule)
    verilog should include ("assign out = b;")
  }

  // TODO Tests to write
  // Aggregate View on target side of another mapping where 1 field comes from outside (should error)
}