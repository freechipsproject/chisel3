---
layout: docs
title:  "General Cookbook"
section: "chisel3"
---

# General Cookbook


Please note that these examples make use of [Chisel's scala-style printing](../explanations/printing#scala-style).

* Type Conversions
  * [How do I create a UInt from an instance of a Bundle?](#how-do-i-create-a-uint-from-an-instance-of-a-bundle)
  * [How do I create a Bundle from a UInt?](#how-do-i-create-a-bundle-from-a-uint)
  * [How can I tieoff a Bundle/Vec to 0?](#how-can-i-tieoff-a-bundlevec-to-0)
  * [How do I create a Vec of Bools from a UInt?](#how-do-i-create-a-vec-of-bools-from-a-uint)
  * [How do I create a UInt from a Vec of Bool?](#how-do-i-create-a-uint-from-a-vec-of-bool)
  * [How do I connect a subset of Bundle fields?](#how-do-i-connect-a-subset-of-bundle-fields)
* Vectors and Registers
  * [How do I create a Vector of Registers?](#how-do-i-create-a-vector-of-registers)
  * [How do I create a Reg of type Vec?](#how-do-i-create-a-reg-of-type-vec)
* [How do I create a finite state machine?](#how-do-i-create-a-finite-state-machine-fsm)
* [How do I unpack a value ("reverse concatenation") like in Verilog?](#how-do-i-unpack-a-value-reverse-concatenation-like-in-verilog)
* [How do I do subword assignment (assign to some bits in a UInt)?](#how-do-i-do-subword-assignment-assign-to-some-bits-in-a-uint)
* [How do I create an optional I/O?](#how-do-i-create-an-optional-io)
* Predictable Naming
  * [How do I get Chisel to name signals properly in blocks like when/withClockAndReset?](#how-do-i-get-chisel-to-name-signals-properly-in-blocks-like-whenwithclockandreset)
  * [How do I get Chisel to name the results of vector reads properly?](#how-do-i-get-chisel-to-name-the-results-of-vector-reads-properly)
  * [How can I dynamically set/parametrize the name of a module?](#how-can-i-dynamically-setparametrize-the-name-of-a-module)

## Type Conversions

### How do I create a UInt from an instance of a Bundle?

Call [`asUInt`](https://www.chisel-lang.org/api/latest/chisel3/Bundle.html#asUInt():chisel3.UInt) on the [`Bundle`](https://www.chisel-lang.org/api/latest/chisel3/Bundle.html) instance.

```scala mdoc:silent:reset
import chisel3._

class MyBundle extends Bundle {
  val foo = UInt(4.W)
  val bar = UInt(4.W)
}

class Foo extends RawModule {
  val bundle = Wire(new MyBundle)
  bundle.foo := 0xc.U
  bundle.bar := 0x3.U
  val uint = bundle.asUInt
  printf(p"$uint") // 195

  // Test
  assert(uint === 0xc3.U)
}
```

### How do I create a Bundle from a UInt?

Use the [`asTypeOf`](https://www.chisel-lang.org/api/latest/chisel3/UInt.html#asTypeOf[T%3C:chisel3.Data](that:T):T) method to reinterpret the [`UInt`](https://www.chisel-lang.org/api/latest/chisel3/UInt.html) as the type of the [`Bundle`](https://www.chisel-lang.org/api/latest/chisel3/Bundle.html).

```scala mdoc:silent:reset
import chisel3._

class MyBundle extends Bundle {
  val foo = UInt(4.W)
  val bar = UInt(4.W)
}

class Foo extends RawModule {
  val uint = 0xb4.U
  val bundle = uint.asTypeOf(new MyBundle)
  
  printf(p"$bundle") // Bundle(foo -> 11, bar -> 4)

  // Test
  assert(bundle.foo === 0xb.U)
  assert(bundle.bar === 0x4.U)
}
```

### How can I tieoff a Bundle/Vec to 0?

You can use `asTypeOf` as above. If you don't want to worry about the type of the thing
you are tying off, you can use `chiselTypeOf`:

```scala mdoc:silent:reset
import chisel3._

class MyBundle extends Bundle {
  val foo = UInt(4.W)
  val bar = Vec(4, UInt(1.W))
}

class Foo(typ: Data) extends RawModule {
  val bundleA = IO(Output(typ))
  val bundleB = IO(Output(typ))
  
  // typ is already a Chisel Data Type, so can use it directly here, but you 
  // need to know that bundleA is of type typ
  bundleA := 0.U.asTypeOf(typ)
  
  // bundleB is a Hardware data IO(Output(...)) so need to call chiselTypeOf,
  // but this will work no matter the type of bundleB:
  bundleB := 0.U.asTypeOf(chiselTypeOf(bundleB)) 
}

class Bar extends RawModule {
  val foo = Module(new Foo(new MyBundle()))
}
```
### How do I create a Vec of Bools from a UInt?

Use [`VecInit`](https://www.chisel-lang.org/api/latest/chisel3/VecInit$.html) given a `Seq[Bool]` generated using the [`asBools`](https://www.chisel-lang.org/api/latest/chisel3/UInt.html#asBools():Seq[chisel3.Bool]) method.

```scala mdoc:silent:reset
import chisel3._

class Foo extends RawModule {
  val uint = 0xc.U
  val vec = VecInit(uint.asBools)

  printf(p"$vec") // Vec(0, 0, 1, 1)

  // Test
  assert(vec(0) === false.B)
  assert(vec(1) === false.B)
  assert(vec(2) === true.B)
  assert(vec(3) === true.B)
}
```

### How do I create a UInt from a Vec of Bool?

Use the builtin function [`asUInt`](https://www.chisel-lang.org/api/latest/chisel3/Vec.html#asUInt():chisel3.UInt)

```scala mdoc:silent:reset
import chisel3._

class Foo extends RawModule {
  val vec = VecInit(true.B, false.B, true.B, true.B)
  val uint = vec.asUInt

  printf(p"$uint") // 13

  // Test
  // (remember leftmost Bool in Vec is low order bit)
  assert(0xd.U === uint)

}
```

### How do I connect a subset of Bundle fields?

See the [DataView cookbook](dataview#how-do-i-connect-a-subset-of-bundle-fields).

## Vectors and Registers

### How do I create a Vector of Registers?

**Rule!  Use Reg of Vec not Vec of Reg!**

You create a [Reg of type Vec](#how-do-i-create-a-reg-of-type-vec). Because Vecs are a *type* (like `UInt`, `Bool`) rather than a *value*, we must bind the Vec to some concrete *value*.

### How do I create a Reg of type Vec?

For more information, the API Documentation for [`Vec`](https://www.chisel-lang.org/api/latest/chisel3/Vec.html) provides more information.

```scala mdoc:silent:reset
import chisel3._

class Foo extends RawModule {
  val regOfVec = Reg(Vec(4, UInt(32.W))) // Register of 32-bit UInts
  regOfVec(0) := 123.U                   // Assignments to elements of the Vec
  regOfVec(1) := 456.U
  regOfVec(2) := 789.U
  regOfVec(3) := regOfVec(0)

  // Reg of Vec of 32-bit UInts initialized to zero
  //   Note that Seq.fill constructs 4 32-bit UInt literals with the value 0
  //   VecInit(...) then constructs a Wire of these literals
  //   The Reg is then initialized to the value of the Wire (which gives it the same type)
  val initRegOfVec = RegInit(VecInit(Seq.fill(4)(0.U(32.W))))
}
```

### How do I create a finite state machine (FSM)?

The advised way is to use [`ChiselEnum`](https://www.chisel-lang.org/api/latest/chisel3/experimental/index.html#ChiselEnum=chisel3.experimental.EnumFactory) to construct enumerated types representing the state of the FSM.
State transitions are then handled with [`switch`](https://www.chisel-lang.org/api/latest/chisel3/util/switch$.html)/[`is`](https://www.chisel-lang.org/api/latest/chisel3/util/is$.html) and [`when`](https://www.chisel-lang.org/api/latest/chisel3/when$.html)/[`.elsewhen`](https://www.chisel-lang.org/api/latest/chisel3/WhenContext.html#elsewhen(elseCond:=%3Echisel3.Bool)(block:=%3EUnit)(implicitsourceInfo:chisel3.internal.sourceinfo.SourceInfo,implicitcompileOptions:chisel3.CompileOptions):chisel3.WhenContext)/[`.otherwise`](https://www.chisel-lang.org/api/latest/chisel3/WhenContext.html#otherwise(block:=%3EUnit)(implicitsourceInfo:chisel3.internal.sourceinfo.SourceInfo,implicitcompileOptions:chisel3.CompileOptions):Unit).

```scala mdoc:silent:reset
import chisel3._
import chisel3.util.{switch, is}
import chisel3.experimental.ChiselEnum

object DetectTwoOnes {
  object State extends ChiselEnum {
    val sNone, sOne1, sTwo1s = Value
  }
}

/* This FSM detects two 1's one after the other */
class DetectTwoOnes extends Module {
  import DetectTwoOnes.State
  import DetectTwoOnes.State._

  val io = IO(new Bundle {
    val in = Input(Bool())
    val out = Output(Bool())
    val state = Output(State())
  })

  val state = RegInit(sNone)

  io.out := (state === sTwo1s)
  io.state := state

  switch (state) {
    is (sNone) {
      when (io.in) {
        state := sOne1
      }
    }
    is (sOne1) {
      when (io.in) {
        state := sTwo1s
      } .otherwise {
        state := sNone
      }
    }
    is (sTwo1s) {
      when (!io.in) {
        state := sNone
      }
    }
  }
}
```

Note: the `is` statement can take multiple conditions e.g. `is (sTwo1s, sOne1) { ... }`.

### How do I unpack a value ("reverse concatenation") like in Verilog?

In Verilog, you can do something like the following which will unpack a the value `z`:

```verilog
wire [1:0] a;
wire [3:0] b;
wire [2:0] c;
wire [8:0] z = [...];
assign {a,b,c} = z;
```

Unpacking often corresponds to reinterpreting an unstructured data type as a structured data type.
Frequently, this structured type is used prolifically in the design, and has been declared as in the following example:

```scala mdoc:silent:reset
import chisel3._

class MyBundle extends Bundle {
  val a = UInt(2.W)
  val b = UInt(4.W)
  val c = UInt(3.W)
}
```

The easiest way to accomplish this in Chisel would be:

```scala mdoc:silent
class Foo extends RawModule {
  val z = Wire(UInt(9.W))
  z := DontCare // This is a dummy connection
  val unpacked = z.asTypeOf(new MyBundle)
  printf("%d", unpacked.a)
  printf("%d", unpacked.b)
  printf("%d", unpacked.c)
}
```

If you **really** need to do this for a one-off case (Think thrice! It is likely you can better structure the code using bundles), then rocket-chip has a [Split utility](https://github.com/freechipsproject/rocket-chip/blob/723af5e6b69e07b5f94c46269a208a8d65e9d73b/src/main/scala/util/Misc.scala#L140) which can accomplish this.

### How do I do subword assignment (assign to some bits in a UInt)?

You may try to do something like the following where you want to assign only some bits of a Chisel type.
Below, the left-hand side connection to `io.out(0)` is not allowed.

```scala mdoc:silent:reset
import chisel3._
import chisel3.stage.{ChiselStage, ChiselGeneratorAnnotation}

class Foo extends Module {
  val io = IO(new Bundle {
    val bit = Input(Bool())
    val out = Output(UInt(10.W))
  })
  io.out(0) := io.bit
}
```

If you try to compile this, you will get an error.
```scala mdoc:crash
(new ChiselStage).execute(Array("-X", "verilog"), Seq(new ChiselGeneratorAnnotation(() => new Foo)))
```

Chisel3 *does not support subword assignment*.
The reason for this is that subword assignment generally hints at a better abstraction with an aggregate/structured types, i.e., a `Bundle` or a `Vec`.

If you must express it this way, one approach is to blast your `UInt` to a `Vec` of `Bool` and back:

```scala mdoc:silent:reset
import chisel3._

class Foo extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(10.W))
    val bit = Input(Bool())
    val out = Output(UInt(10.W))
  })
  val bools = VecInit(io.in.asBools)
  bools(0) := io.bit
  io.out := bools.asUInt
}
```


### How do I create an optional I/O?

The following example is a module which includes the optional port `out2` only if the given parameter is `true`.

```scala mdoc:silent:reset
import chisel3._

class ModuleWithOptionalIOs(flag: Boolean) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(12.W))
    val out = Output(UInt(12.W))
    val out2 = if (flag) Some(Output(UInt(12.W))) else None
  })

  io.out := io.in
  if (flag) {
    io.out2.get := io.in
  }
}
```

The following is an example where an entire `IO` is optional:

```scala mdoc:silent:reset
import chisel3._

class ModuleWithOptionalIO(flag: Boolean) extends Module {
  val in = if (flag) Some(IO(Input(Bool()))) else None
  val out = IO(Output(Bool()))

  out := in.getOrElse(false.B)
}
```

## Predictable Naming

### How do I get Chisel to name signals properly in blocks like when/withClockAndReset?

Use the compiler plugin, and check out the [Naming Cookbook](#naming) if that still does not do what you want.

### How do I get Chisel to name the results of vector reads properly?
Currently, name information is lost when using dynamic indexing. For example:
```scala mdoc:silent
class Foo extends Module {
  val io = IO(new Bundle {
    val in = Input(Vec(4, Bool()))
    val idx = Input(UInt(2.W))
    val en = Input(Bool())
    val out = Output(Bool())
  })

  val x = io.in(io.idx)
  val y = x && io.en
  io.out := y
}
```

The above code loses the `x` name, instead using `_GEN_3` (the other `_GEN_*` signals are expected).
```verilog
module Foo(
  input        clock,
  input        reset,
  input        io_in_0,
  input        io_in_1,
  input        io_in_2,
  input        io_in_3,
  input  [1:0] io_idx,
  input        io_en,
  output       io_out
);
  wire  _GEN_1; // @[main.scala 15:13]
  wire  _GEN_2; // @[main.scala 15:13]
  wire  _GEN_3; // @[main.scala 15:13]
  assign _GEN_1 = 2'h1 == io_idx ? io_in_1 : io_in_0; // @[main.scala 15:13]
  assign _GEN_2 = 2'h2 == io_idx ? io_in_2 : _GEN_1; // @[main.scala 15:13]
  assign _GEN_3 = 2'h3 == io_idx ? io_in_3 : _GEN_2; // @[main.scala 15:13]
  assign io_out = _GEN_3 & io_en; // @[main.scala 16:10]
endmodule
```

This can be worked around by creating a wire and connecting the dynamic index to the wire:
```scala
val x = WireInit(io.in(io.idx))
```

Which produces:
```verilog
module Foo(
  input        clock,
  input        reset,
  input        io_in_0,
  input        io_in_1,
  input        io_in_2,
  input        io_in_3,
  input  [1:0] io_idx,
  input        io_en,
  output       io_out
);
  wire  _GEN_1;
  wire  _GEN_2;
  wire  x;
  assign _GEN_1 = 2'h1 == io_idx ? io_in_1 : io_in_0;
  assign _GEN_2 = 2'h2 == io_idx ? io_in_2 : _GEN_1;
  assign x = 2'h3 == io_idx ? io_in_3 : _GEN_2;
  assign io_out = x & io_en; // @[main.scala 16:10]
endmodule
```
### How can I dynamically set/parametrize the name of a module?

You can override the `desiredName` function. This works with normal Chisel modules and `BlackBox`es. Example:

```scala mdoc:silent:reset
import chisel3._

class Coffee extends BlackBox {
    val io = IO(new Bundle {
        val I = Input(UInt(32.W))
        val O = Output(UInt(32.W))
    })
    override def desiredName = "Tea"
}

class Salt extends Module {
    val io = IO(new Bundle {})
    val drink = Module(new Coffee)
    override def desiredName = "SodiumMonochloride"
}
```

Elaborating the Chisel module `Salt` yields our "desired names" for `Salt` and `Coffee` in the output Verilog:
```scala mdoc:silent
import chisel3.stage.ChiselStage

ChiselStage.emitVerilog(new Salt)
```

```scala mdoc:verilog
ChiselStage.emitVerilog(new Salt)
```
