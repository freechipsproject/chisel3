// See LICENSE for license details.

package chiselTests

import chisel3._
import chisel3.core.Binding
import chisel3.testers.BasicTester
import chisel3.util._
import org.scalacheck.Shrink

class MixedVecAssignTester(w: Int, values: List[Int]) extends BasicTester {
  val v = MixedVecWireInit(values.map(v => v.U(w.W)))
  for ((a, b) <- v.zip(values)) {
    assert(a === b.asUInt)
  }
  stop()
}

class MixedVecRegTester(w: Int, values: List[Int]) extends BasicTester {
  val valuesInit = MixedVecWireInit(values.map(v => v.U(w.W)))
  val reg = Reg(MixedVec(chiselTypeOf(valuesInit)))

  val doneReg = RegInit(false.B)
  doneReg := true.B

  when(!doneReg) {
    // First cycle: write to reg
    reg := valuesInit
  }.otherwise {
    // Second cycle: read back from reg
    for ((a, b) <- reg.zip(values)) {
      assert(a === b.asUInt)
    }
    stop()
  }
}

class MixedVecIOPassthroughModule[T <: Data](hvec: MixedVec[T]) extends Module {
  val io = IO(new Bundle {
    val in = Input(hvec)
    val out = Output(hvec)
  })
  io.out := io.in
}

class MixedVecIOTester(boundVals: Seq[Data]) extends BasicTester {
  val v = MixedVecWireInit(boundVals)
  val dut = Module(new MixedVecIOPassthroughModule(MixedVec(chiselTypeOf(v))))
  dut.io.in := v
  for ((a, b) <- dut.io.out.zip(boundVals)) {
    assert(a.asUInt === b.asUInt)
  }
  stop()
}

class MixedVecZeroEntryTester extends BasicTester {
  def zeroEntryMixedVec: MixedVec[Data] = MixedVec(Seq.empty)

  require(zeroEntryMixedVec.getWidth == 0)

  val bundleWithZeroEntryVec = new Bundle {
    val foo = Bool()
    val bar = zeroEntryMixedVec
  }
  require(0.U.asTypeOf(bundleWithZeroEntryVec).getWidth == 1)
  require(bundleWithZeroEntryVec.asUInt.getWidth == 1)

  val m = Module(new Module {
    val io = IO(Output(bundleWithZeroEntryVec))
    io.foo := false.B
  })
  WireInit(m.io.bar)

  stop()
}

class MixedVecDynamicIndexTester(n: Int) extends BasicTester {
  val wire = Wire(MixedVec(Seq.fill(n) { UInt() }))

  val (cycle, done) = Counter(true.B, n)

  for (i <- 0 until n) {
    when(cycle === i.U) {
      wire(i) := i.U
    } .otherwise {
      wire(i) := DontCare
    }
  }

  assert(wire(cycle) === cycle)

  when (done) { stop() }
}

class MixedVecFromVecTester extends BasicTester {
  val wire = Wire(MixedVec(Vec(3, UInt(8.W))))
  wire := MixedVecWireInit(Seq(20.U, 40.U, 80.U))

  assert(wire(0) === 20.U)
  assert(wire(1) === 40.U)
  assert(wire(2) === 80.U)

  stop()
}

class MixedVecConnectWithVecTester extends BasicTester {
  val mixedVecType = MixedVec(Vec(3, UInt(8.W)))

  val m = Module(new MixedVecIOPassthroughModule(mixedVecType))
  m.io.in := VecInit(Seq(20.U, 40.U, 80.U))
  val wire = m.io.out

  assert(wire(0) === 20.U)
  assert(wire(1) === 40.U)
  assert(wire(2) === 80.U)

  stop()
}

class MixedVecConnectWithSeqTester extends BasicTester {
  val mixedVecType = MixedVec(Vec(3, UInt(8.W)))

  val m = Module(new MixedVecIOPassthroughModule(mixedVecType))
  m.io.in := Seq(20.U, 40.U, 80.U)
  val wire = m.io.out

  assert(wire(0) === 20.U)
  assert(wire(1) === 40.U)
  assert(wire(2) === 80.U)

  stop()
}

class MixedVecOneBitTester extends BasicTester {
  val flag = RegInit(false.B)

  val oneBit = Reg(MixedVec(Seq(UInt(1.W))))
  when (!flag) {
    oneBit(0) := 1.U(1.W)
    flag := true.B
  } .otherwise {
    assert(oneBit(0) === 1.U)
    assert(oneBit.asUInt === 1.U)
    stop()
  }
}

class MixedVecSpec extends ChiselPropSpec {
  // Disable shrinking on error.
  // Not sure why this needs to be here, but the test behaves very weirdly without it (e.g. empty Lists, etc).
  implicit val noShrinkListVal = Shrink[List[Int]](_ => Stream.empty)
  implicit val noShrinkInt = Shrink[Int](_ => Stream.empty)

  property("MixedVecs should be assignable") {
    forAll(safeUIntN(8)) { case (w: Int, v: List[Int]) =>
      assertTesterPasses {
        new MixedVecAssignTester(w, v)
      }
    }
  }

  property("MixedVecs should be usable as the type for Reg()") {
    forAll(safeUIntN(8)) { case (w: Int, v: List[Int]) =>
      assertTesterPasses {
        new MixedVecRegTester(w, v)
      }
    }
  }

  property("MixedVecs should be passed through IO") {
    forAll(safeUIntN(8)) { case (w: Int, v: List[Int]) =>
      assertTesterPasses {
        new MixedVecIOTester(v.map(i => i.U(w.W)))
      }
    }
  }

  property("MixedVecs should work with mixed types") {
    assertTesterPasses {
      new MixedVecIOTester(Seq(true.B, 168.U(8.W), 888.U(10.W), -3.S))
    }
  }

  property("MixedVecs should not be able to take hardware types") {
    a [Binding.ExpectedChiselTypeException] should be thrownBy {
      elaborate(new Module {
        val io = IO(new Bundle {})
        val hw = Wire(MixedVec(Seq(UInt(8.W), Bool())))
        val illegal = MixedVec(hw)
      })
    }
    a [Binding.ExpectedChiselTypeException] should be thrownBy {
      elaborate(new Module {
        val io = IO(new Bundle {})
        val hw = Reg(MixedVec(Seq(UInt(8.W), Bool())))
        val illegal = MixedVec(hw)
      })
    }
    a [Binding.ExpectedChiselTypeException] should be thrownBy {
      elaborate(new Module {
        val io = IO(new Bundle {
          val v = Input(MixedVec(Seq(UInt(8.W), Bool())))
        })
        val illegal = MixedVec(io.v)
      })
    }
  }

  property("MixedVecs with zero entries should compile and have zero width") {
    assertTesterPasses { new MixedVecZeroEntryTester }
  }

  property("MixedVecs should be dynamically indexable") {
    assertTesterPasses{ new MixedVecDynamicIndexTester(4) }
  }

  property("MixedVecs should be creatable from Vecs") {
    assertTesterPasses{ new MixedVecFromVecTester }
  }

  property("It should be possible to bulk connect a MixedVec and a Vec") {
    assertTesterPasses{ new MixedVecConnectWithVecTester }
  }

  property("It should be possible to bulk connect a MixedVec and a Seq") {
    assertTesterPasses{ new MixedVecConnectWithSeqTester }
  }

  property("MixedVecs of a single 1 bit element should compile and work") {
    assertTesterPasses { new MixedVecOneBitTester }
  }

  property("Connecting a MixedVec and something of different size should report a ChiselException") {
    an [IllegalArgumentException] should be thrownBy {
      elaborate(new Module {
        val io = IO(new Bundle {
          val out = Output(MixedVec(Seq(UInt(8.W), Bool())))
        })
        val seq = Seq.fill(5)(0.U)
        io.out := seq
      })
    }
    an [IllegalArgumentException] should be thrownBy {
      elaborate(new Module {
        val io = IO(new Bundle {
          val out = Output(MixedVec(Seq(UInt(8.W), Bool())))
        })
        val seq = VecInit(Seq(100.U(8.W)))
        io.out := seq
      })
    }
  }
}
