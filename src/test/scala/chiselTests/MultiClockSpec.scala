// See LICENSE for license details.

package chiselTests

import chisel3._
import chisel3.util.{Counter, RegInit}
import chisel3.testers.BasicTester

class ClockDividerTest extends BasicTester {
  val cDiv = RegInit(true.B) // start with falling edge to simplify clock relationship assert
  cDiv := !cDiv
  val clock2 = cDiv.asClock
  val reset2 = Wire(init = reset)

  val reg1 = RegInit(0.U(8.W))
  reg1 := reg1 + 1.U
  val reg2 = withClockAndReset(clock2, reset2) { RegInit(0.U(8.W)) }
  reg2 := reg2 + 1.U

  when (reg1 < 10.U) {
    assert(reg2 === reg1 / 2.U) // 1:2 clock relationship
  }

  when (reg1 === 10.U) {
    reset2 := true.B
  }
  when (reg1 === 11.U) {
    assert(reg2 === 0.U)
    stop()
  }
}

class MultiClockMemTest extends BasicTester {
  val cDiv = RegInit(true.B)
  cDiv := !cDiv
  val clock2 = cDiv.asClock

  val mem = Mem(8, UInt(32.W))

  val (cycle, done) = Counter(true.B, 20)

  // Write port 1 walks through writing 123
  val waddr = RegInit(0.U(3.W))
  waddr := waddr + 1.U
  when (cycle < 8.U) {
    mem(waddr) := 123.U
  }

  val raddr = waddr - 1.U
  val rdata = mem(raddr)

  // Check each write from write port 1
  when (cycle > 0.U && cycle < 9.U) {
    assert(rdata === 123.U)
  }

  // Write port 2 walks through writing 456 on 2nd time through
  withClockAndReset(clock2, reset) {
    when (cycle >= 8.U && cycle < 16.U) {
      mem(waddr) := 456.U // write 456 to different address
    }
  }

  // Check that every even address gets 456
  when (cycle > 8.U && cycle < 17.U) {
    when (raddr % 2.U === 0.U) {
      assert(rdata === 456.U)
    } .otherwise {
      assert(rdata === 123.U)
    }
  }

  when (done) { stop() }
}

class MultiClockSpec extends ChiselFlatSpec {
  behavior of "withClockAndReset"

  it should "return like a normal Scala block" in {
    Driver.emit(() => new Module {
      val io = IO(new Bundle {})
      val res = withClockAndReset(this.clock, this.reset) { 5 }
      assert(res === 5)
    })
  }

  it should "scope the clock and reset of registers" in {
    assertTesterPasses(new ClockDividerTest)
  }

  it should "scope ports of memories" in {
    assertTesterPasses(new MultiClockMemTest)
  }

  it should "scope the clocks and resets of asserts" in {
    // Check that assert can fire
    assertTesterFails(new BasicTester {
      withClockAndReset(clock, reset) {
        chisel3.assert(0.U === 1.U)
      }
      val (_, done) = Counter(true.B, 2)
      when (done) { stop() }
    })
    // Check that reset will block
    assertTesterPasses(new BasicTester {
      withClockAndReset(clock, true.B) {
        chisel3.assert(0.U === 1.U)
      }
      val (_, done) = Counter(true.B, 2)
      when (done) { stop() }
    })
    // Check that no rising edge will block
    assertTesterPasses(new BasicTester {
      withClockAndReset(false.B.asClock, reset) {
        chisel3.assert(0.U === 1.U)
      }
      val (_, done) = Counter(true.B, 2)
      when (done) { stop() }
    })
  }
}
