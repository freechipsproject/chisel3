---
layout: docs
title:  "DataView"
section: "chisel3"
---

# DataView

_New in Chisel 3.5_

```scala mdoc:invisible
import chisel3._
import chisel3.stage.ChiselStage.emitVerilog
```

## Introduction

DataView is a mechanism for "viewing" Scala objects as a subtype of `chisel3.Data`.
Often, this is useful for viewing one subtype of `chisel3.Data`, as another.
One can think about a `DataView` as a mapping from a _Target_ type `T` to a _View_ type `V`.
This is similar to a cast (eg. `.asTypeOf`) with a few differences:
1. Views are _connectable_—connections to the view will occur on the target
2. Whereas casts are _structural_ (a reinterpretation of the underlying bits), a DataView is a customizable mapping
3. Views can be _partial_—not every field in the target must be included in the mapping

## A Motivating Example (AXI4)

[AXI4](https://en.wikipedia.org/wiki/Advanced_eXtensible_Interface) is a common interface in digital
design.
A typical Verilog peripheral using AXI4 will define a write channel as something like:
```verilog
module my_module(
  // Write Channel
  input        AXI_AWVALID,
  output       AXI_AWREADY,
  input [3:0]  AXI_AWID,
  input [19:0] AXI_AWADDR,
  input [1:0]  AXI_AWLEN,
  input [1:0]  AXI_AWSIZE,
  // ...
);
```

This would correspond to the following Chisel Bundle:

```scala mdoc
class VerilogAXIBundle extends Bundle {
  val AWVALID = Output(Bool())
  val AWREADY = Input(Bool())
  val AWID = Output(UInt(4.W))
  val AWADDR = Output(UInt(20.W))
  val AWLEN = Output(UInt(2.W))
  val AWSIZE = Output(UInt(2.W))
  // The rest of AW and other AXI channels here
}

// Instantiated as
class my_module extends RawModule {
  val AXI = IO(new VerilogAXIBundle)
}
```

Expressing something that matches a standard Verilog interface is important when instantiating Verilog
modules in a Chisel design as `BlackBoxes`.
Generally though, Chisel developers prefer to use composition via utilities like `Decoupled` rather
than a flat handling of `ready` and `valid` as in the above.
A more "Chisel-y" implementation of this interface might look like:

```scala mdoc
// Note that both the AW and AR channels look similar and could use the same Bundle definition
class AXIAddressChannel extends Bundle {
  val id = UInt(4.W)
  val addr = UInt(20.W)
  val len = UInt(2.W)
  val size = UInt(2.W)
  // ...
}
import chisel3.util.Decoupled
// We can compose the various AXI channels together
class AXIBundle extends Bundle {
  val aw = Decoupled(new AXIAddressChannel)
  // val ar = new AXIAddressChannel
  // ... Other channels here ...
}
// Instantiated as
class MyModule extends RawModule {
  val axi = IO(new AXIBundle)
}
```

Of course, this would result in very different looking Verilog:

```scala mdoc:verilog
emitVerilog(new MyModule {
  override def desiredName = "MyModule"
  axi := DontCare // Just to generate Verilog in this stub
})
```

So how can we use our more structured types while maintaining expected Verilog interfaces?
Meet DataView:

```scala mdoc
import chisel3.experimental.dataview._

// We recommend putting DataViews in a companion object of one of the involved types
object AXIBundle {
  // Don't be afraid of the use of implicits, we will discuss this pattern in more detail later
  implicit val axiView = DataView[VerilogAXIBundle, AXIBundle](
    _.AWVALID -> _.aw.valid,
    _.AWREADY -> _.aw.ready,
    _.AWID -> _.aw.bits.id,
    _.AWADDR -> _.aw.bits.addr,
    _.AWLEN -> _.aw.bits.len,
    _.AWSIZE -> _.aw.bits.size,
    // ...
  )
}
```

This `DataView` is a _bidirectional_ mapping between our flat, Verilog-style AXI Bundle and our more
compositional, Chisel-style AXI Bundle.
It allows us to define our ports to match the expected Verilog interface, while manipulating it as if
it were the more structured type:

```scala mdoc
class AXIStub extends RawModule {
  val AXI = IO(new VerilogAXIBundle)
  val view = AXI.viewAs(new AXIBundle)

  // We can now manipulate `AXI` via `view`
  view.aw.bits := 0.U.asTypeOf(new AXIAddressChannel) // zero everything out by default
  view.aw.valid := true.B
  when (view.aw.ready) {
    view.aw.bits.id := 5.U
    view.aw.bits.addr := 1234.U
    // We can still manipulate AXI as well
    AXI.AWLEN := 1.U
  }
}
```

This will generate Verilog that matches the standard naming convention:

```scala mdoc:verilog
emitVerilog(new AXIStub)
```

Note that if both the _Target_ and the _View_ types are subtypes of `Data` (as they are in this example),
the `DataView` is _invertible_. This means that in addition to viewing `VerilogAXIBundles` as `AXIBundles`,
we can view `AXIBundles` as `VerilogAXIBundles`.
The following example shows this and illustrates another use case of `DataView`—connecting unrelated
types:

```scala mdoc
class ConnectionExample extends RawModule {
  val in = IO(new AXIBundle)
  val out = IO(Flipped(new VerilogAXIBundle))
  out.viewAs(new AXIBundle) <> in
}
```

This results in the corresponding fields being connected in the emitted Verilog:

```scala mdoc:verilog
emitVerilog(new ConnectionExample)
```

## Other Use Cases

While the ability to map between `Bundle` types as in the AXI4 example is pretty compelling,
DataView has many other applications.
Importantly, because the _Target_ of the `DataView` need not be a `Data`, it provides a way to use
`non-Data` objects with APIs that require `Data`.

### Tuples

Perhaps the most helpful use of `DataView` for a non-`Data` type is viewing Scala tuples as `Bundles`.
For example, in Chisel prior to the introduction of `DataView`, one might try to `Mux` tuples and
see an error like the following:

<!-- Todo will need to ensure built-in code for Tuples is suppressed once added to stdlib -->

```scala mdoc:fail
class TupleExample extends RawModule {
  val a, b, c, d = IO(Input(UInt(8.W)))
  val cond = IO(Input(Bool()))
  val x, y = IO(Output(UInt(8.W)))
  (x, y) := Mux(cond, (a, b), (c, d))
}
```

The issue, is that Chisel primitives like `Mux` and `:=` only operate on subtypes of `Data` and
Tuples (as members of the Scala standard library), are not subclasses of `Data`.
`DataView` provides a mechanism to _view_ a `Tuple` as if it were a `Data`:

<!-- TODO replace this with stdlib import -->

```scala mdoc:invisible
// ProductDataProduct
implicit val productDataProduct: DataProduct[Product] = new DataProduct[Product] {
  def dataIterator(a: Product, path: String): Iterator[(Data, String)] = {
    a.productIterator.zipWithIndex.collect { case (d: Data, i) => d -> s"$path._$i" }
  }
}
```

```scala mdoc
// We need a type to represent the Tuple
class HWTuple2[A <: Data, B <: Data](val _1: A, val _2: B) extends Bundle

// Provide DataView mapping between Tuple and HWTuple
implicit def view[A <: Data, B <: Data]: DataView[(A, B), HWTuple2[A, B]] =
  DataView(_._1 -> _._1, _._2 -> _._2)
```

Now, we can use `.viewAs` to view Tuples as if they were subtypes of `Data`:

```scala mdoc
class TupleVerboseExample extends RawModule {
  val a, b, c, d = IO(Input(UInt(8.W)))
  val cond = IO(Input(Bool()))
  val x, y = IO(Output(UInt(8.W)))
  val tupleTpe = new HWTuple2(UInt(), UInt())
  (x, y).viewAs(tupleTpe) := Mux(cond, (a, b).viewAs(tupleTpe), (c, d).viewAs(tupleTpe))
}
```

This is much more verbose than the original idea of just using the Tuples directly as if they were `Data`.
We can make this better by providing an implicit conversion that views a `Tuple` as a `HWTuple2`:

```scala mdoc
implicit def tuple2hwtuple[A <: Data, B <: Data](tup: (A, B)): HWTuple2[A, B] =
  tup.viewAs(new HWTuple2(tup._1.cloneType, tup._2.cloneType))
```

Now, the original code just works!

```scala mdoc
class TupleExample extends RawModule {
  val a, b, c, d = IO(Input(UInt(8.W)))
  val cond = IO(Input(Bool()))
  val x, y = IO(Output(UInt(8.W)))
  (x, y) := Mux(cond, (a, b), (c, d))
}
```

```scala mdoc:invisible
// Always emit Verilog to make sure it actually works
emitVerilog(new TupleExample)
```

Note that this example ignored `DataProduct` which is another required piece (see [the documentation
about it below](#dataproduct)).

All of this is slated to be included the Chisel standard library.

## Totality and PartialDataView

A `DataView` is _total_ if all fields of the _Target_ type and all fields of the _View_ type are 
included in the mapping.
Chisel will error if a field is accidentally left out from a `DataView`.
For example:

```scala mdoc
class BundleA extends Bundle {
  val foo = UInt(8.W)
  val bar = UInt(8.W)
}
class BundleB extends Bundle {
  val fizz = UInt(8.W)
}
```

```scala mdoc:crash
{ // Using an extra scope here to avoid a bug in mdoc (documentation generation)
// We forgot BundleA.foo in the mapping!
implicit val myView = DataView[BundleA, BundleB](_.bar -> _.fizz)
class BadMapping extends Module {
   val in = IO(Input(new BundleA))
   val out = IO(Output(new BundleB))
   out := in.viewAs(new BundleB)
}
// We must run Chisel to see the error
emitVerilog(new BadMapping)
}
```

As that error suggests, if we *want* the view to be non-total, we can use a `PartialDataView`:

```scala mdoc
// A PartialDataView does not have to be total for the Target
implicit val myView = PartialDataView[BundleA, BundleB](_.bar -> _.fizz)
class PartialDataViewModule extends Module {
   val in = IO(Input(new BundleA))
   val out = IO(Output(new BundleB))
   out := in.viewAs(new BundleB)
}
```

```scala mdoc:verilog
emitVerilog(new PartialDataViewModule)
```

While `PartialDataViews` need not be total for the _Target_, both `PartialDataViews` and `DataViews`
must always be total for the _View_.
This has the consequence that `PartialDataViews` are **not** invertible in the same way as `DataViews`.

For example:

```scala mdoc:crash
{ // Using an extra scope here to avoid a bug in mdoc (documentation generation)
class PartialDataViewModule2 extends Module {
   val in = IO(Input(new BundleA))
   val out = IO(Output(new BundleB))
   // Using the inverted version of the mapping
   out.viewAs(new BundleA) := in
}
// We must run Chisel to see the error
emitVerilog(new PartialDataViewModule2)
}
```

As noted, the mapping must **always** be total for the `View`.

## Advanced Details

`DataView` takes advantage of features of Scala that may be new to many users of Chisel—in particular
[Type Classes](#type-classes).

### Type Classes

[Type classes](https://en.wikipedia.org/wiki/Type_class) are powerful language feature for writing
polymorphic code.
They are a common feature in "modern programming languages" like
Scala,
Swift (see [protocols](https://docs.swift.org/swift-book/LanguageGuide/Protocols.html)),
and Rust (see [traits](https://doc.rust-lang.org/book/ch10-02-traits.html)). 
Type classes may appear similar to inheritance in object-oriented programming but there are some
important  differences:

1. You can provide a type class for a type you don't own (eg. one defined in a 3rd party library,
  the Scala standard library, or Chisel itself)
2. You can write a single type class for many types that do not have a sub-typing relationship
3. You can provide multiple different type classes for the same type

For `DataView`, (1) is crucial because we want to be able to implement `DataViews` of built-in Scala
types like tuples and `Seqs`. Furthermore, `DataView` has two type parameters (the _Target_ and the
_View_ types) so inheritance does not really make sense—which type would `extend` `DataView`?

In Scala 2, type classes are not a built-in language feature, but rather are implemented using implicits.
There are great resources out there for interested readers:
* [Basic Tutorial](https://scalac.io/blog/typeclasses-in-scala/)
* [Fantastic Explanation on StackOverflow](https://stackoverflow.com/a/5598107/2483329)

Note that Scala 3 has added built-in syntax for type classes that does not apply to Chisel 3 which
currently only supports Scala 2.

### Implicit Resolution

Given that `DataView` is implemented using implicits, it is important to understand implicit
resolution.
Whenever the compiler sees an implicit argument is required, it first looks in _current scope_
before looking in the _implicit scope_.

1. Current scope
    * Values defined in the current scope
    * Explicit imports
    * Wildcard imports
2. Implicit scope
    * Companion object of a type
    * Implicit scope of an argument's type
    * Implicit scope of type parameters
    
If at either stage, multiple implicits are found, then the static overloading rule is used to resolve
it.
Put simply, if one implicit applies to a more-specific type than the other, the more-specific one
will be selected.
If multiple implicits apply within a given stage, then the compiler throws an ambiguous implicit
resolution error.


This section draws heavily from [[1]](https://stackoverflow.com/a/5598107/2483329) and
[[2]](https://stackoverflow.com/a/5598107/2483329).
In particular, see [1] for examples.

#### Implicit Resolution Example

To help clarify a bit, let us consider how implicit resolution works for `DataView`.
Consider the definition of `viewAs`:

```scala
def viewAs[V <: Data](view: V)(implicit dataView: DataView[T, V]): V
```

Armed with the knowledge from the previous section, we know that whenever we call `.viewAs`, the
Scala compiler will first look for a `DataView[T, V]` in the current scope (defined in, or imported),
then it will look in the companion objects of `DataView`, `T`, and `V`.
This enables a fairly powerful pattern, namely that default or typical implementations of a `DataView`
should be defined in the companion object for one of the two types.
We can think about `DataViews` defined in this way as "low priority defaults".
They can then be overruled by a specific import if a given user ever wants different behavior.
For example:

Given the following types:

```scala mdoc
class Foo extends Bundle {
  val a = UInt(8.W)
  val b = UInt(8.W)
}
class Bar extends Bundle {
  val c = UInt(8.W)
  val d = UInt(8.W)
}
object Foo {
  implicit val view = DataView[Foo, Bar](_.a -> _.c, _.b -> _.d)
}
```

This provides an implementation of `DataView` in the _implicit scope_ as a "default" mapping between
`Foo` and `Bar` (and it doesn't even require an import!):

```scala mdoc
class FooToBar extends Module {
  val foo = IO(Input(new Foo))
  val bar = IO(Output(new Bar))
  bar := foo.viewAs(new Bar)
}
```

```scala mdoc:verilog
emitVerilog(new FooToBar)
```

However, it's possible that some user of `Foo` and `Bar` wants different behavior,
perhaps they would prefer more of "swizzling" behavior rather than a direct mapping:

```scala mdoc
object Swizzle {
  implicit val swizzle = DataView[Foo, Bar](_.a -> _.d, _.b -> _.c)
}
// Current scope always wins over implicit scope
import Swizzle._
class FooToBarSwizzled extends Module {
  val foo = IO(Input(new Foo))
  val bar = IO(Output(new Bar))
  bar := foo.viewAs(new Bar)
}
```

```scala mdoc:verilog
emitVerilog(new FooToBarSwizzled)
```

### DataProduct

`DataProduct` is a type class used by `DataView` to validate the correctness of a user-provided mapping.
In order for a type to be "viewable" (ie. the `Target` type of a `DataView`), it must have an
implementation of `DataProduct`.

For example, say we have some non-Bundle type:
```scala mdoc
// Loosely based on chisel3.util.Counter
class MyCounter(width: Int) {
  /** Indicates if the Counter is incrementing this cycle */
  val active = WireDefault(false.B)
  val value = RegInit(0.U(width.W))
  def inc(): Unit = {
    active := true.B
    value := value + 1.U
  }
  def reset(): Unit = {
    value := 0.U
  }
}
```

Say we want to view `MyCounter` as a `Valid[UInt]`:

```scala mdoc:fail
import chisel3.util.Valid
implicit val counterView = DataView[MyCounter, Valid[UInt]](_.value -> _.bits, _.active -> _.valid)
```

As you can see, this fails Scala compliation.
We need to provide an implementation of `DataProduct[MyCounter]` which provides Chisel a way to access
the objects of type `Data` within `MyCounter`:

```scala mdoc:silent
import chisel3.util.Valid
implicit val counterProduct = new DataProduct[MyCounter] {
  // The String part of the tuple is a String path to the object to help in debugging
  def dataIterator(a: MyCounter, path: String): Iterator[(Data, String)] =
    List(a.value -> s"$path.value", a.active -> s"$path.active").iterator
}
// Now this works
implicit val counterView = DataView[MyCounter, Valid[UInt]](_.value -> _.bits, _.active -> _.valid)
```

Why is this useful?
This is how Chisel is able to check for totality as [described above](#totality-and-partialdataview).
In addition to checking if a user has left a field out of the mapping, it also allows Chisel to check
if the user has included a `Data` in the mapping that isn't actually a part of the _target_ nor the
_view_.

