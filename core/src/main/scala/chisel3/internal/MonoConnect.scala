// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.experimental.{Analog, BaseModule, EnumType, FixedPoint, Interval, UnsafeEnum}
import chisel3.internal.Builder.pushCommand
import chisel3.experimental.dataview.reify
import chisel3.internal.firrtl.{Connect, DefInvalid}

import scala.language.experimental.macros
import chisel3.internal.sourceinfo.SourceInfo

/**
* MonoConnect.connect executes a mono-directional connection element-wise.
*
* Note that this isn't commutative. There is an explicit source and sink
* already determined before this function is called.
*
* The connect operation will recurse down the left Data (with the right Data).
* An exception will be thrown if a movement through the left cannot be matched
* in the right. The right side is allowed to have extra Record fields.
* Vecs must still be exactly the same size.
*
* See elemConnect for details on how the root connections are issued.
*
* Note that a valid sink must be writable so, one of these must hold:
* - Is an internal writable node (Reg or Wire)
* - Is an output of the current module
* - Is an input of a submodule of the current module
*
* Note that a valid source must be readable so, one of these must hold:
* - Is an internal readable node (Reg, Wire, Op)
* - Is a literal
* - Is a port of the current module or submodule of the current module
*/

private[chisel3] object MonoConnect {
  // These are all the possible exceptions that can be thrown.
  // These are from element-level connection
  def UnreadableSourceException =
    MonoConnectException(": Source is unreadable from current module.")
  def UnwritableSinkException =
    MonoConnectException(": Sink is unwriteable by current module.")
  def SourceEscapedWhenScopeException =
    MonoConnectException(": Source has escaped the scope of the when in which it was constructed.")
  def SinkEscapedWhenScopeException =
    MonoConnectException(": Sink has escaped the scope of the when in which it was constructed.")
  def UnknownRelationException =
    MonoConnectException(": Sink or source unavailable to current module.")
  // These are when recursing down aggregate types
  def MismatchedVecException =
    MonoConnectException(": Sink and Source are different length Vecs.")
  def MissingFieldException(field: String) =
    MonoConnectException(s": Source Record missing field ($field).")
  def MismatchedException(sink: String, source: String) =
    MonoConnectException(s": Sink ($sink) and Source ($source) have different types.")
  def DontCareCantBeSink =
    MonoConnectException(": DontCare cannot be a connection sink (LHS)")
  def AnalogCantBeMonoSink =
    MonoConnectException(": Analog cannot participate in a mono connection (sink - LHS)")
  def AnalogCantBeMonoSource =
    MonoConnectException(": Analog cannot participate in a mono connection (source - RHS)")
  def AnalogMonoConnectionException =
    MonoConnectException(": Analog cannot participate in a mono connection (source and sink)")

  def checkWhenVisibility(x: Data): Boolean = {
    x.topBinding match {
      case mp: MemoryPortBinding => true // TODO (albert-magyar): remove this "bridge" for odd enable logic of current CHIRRTL memories
      case cd: ConditionalDeclarable => cd.visibility.map(_.active()).getOrElse(true)
      case _ => true
    }
  }

  /** This function is what recursively tries to connect a sink and source together
  *
  * There is some cleverness in the use of internal try-catch to catch exceptions
  * during the recursive decent and then rethrow them with extra information added.
  * This gives the user a 'path' to where in the connections things went wrong.
  */
  def connect(
      sourceInfo: SourceInfo,
      connectCompileOptions: CompileOptions,
      sink: Data,
      source: Data,
      context_mod: RawModule): Unit =
    (sink, source) match {

      // Handle legal element cases, note (Bool, Bool) is caught by the first two, as Bool is a UInt
      case (sink_e: Bool, source_e: UInt) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: UInt, source_e: Bool) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: UInt, source_e: UInt) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: SInt, source_e: SInt) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: FixedPoint, source_e: FixedPoint) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: Interval, source_e: Interval) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: Clock, source_e: Clock) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: AsyncReset, source_e: AsyncReset) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: ResetType, source_e: Reset) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: Reset, source_e: ResetType) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: EnumType, source_e: UnsafeEnum) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: EnumType, source_e: EnumType) if sink_e.typeEquivalent(source_e) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)
      case (sink_e: UnsafeEnum, source_e: UInt) =>
        elemConnect(sourceInfo, connectCompileOptions, sink_e, source_e, context_mod)

      // Handle Vec case
      case (sink_v: Vec[Data @unchecked], source_v: Vec[Data @unchecked]) =>
        if(sink_v.length != source_v.length) { throw MismatchedVecException }
        for(idx <- 0 until sink_v.length) {
          try {
            implicit val compileOptions = connectCompileOptions
            connect(sourceInfo, connectCompileOptions, sink_v(idx), source_v(idx), context_mod)
          } catch {
            case MonoConnectException(message) => throw MonoConnectException(s"($idx)$message")
          }
        }
      // Handle Vec connected to DontCare. Apply the DontCare to individual elements.
      case (sink_v: Vec[Data @unchecked], DontCare) =>
        for(idx <- 0 until sink_v.length) {
          try {
            implicit val compileOptions = connectCompileOptions
            connect(sourceInfo, connectCompileOptions, sink_v(idx), source, context_mod)
          } catch {
            case MonoConnectException(message) => throw MonoConnectException(s"($idx)$message")
          }
        }

      // Handle Record case
      case (sink_r: Record, source_r: Record) =>
        // For each field, descend with right
        for((field, sink_sub) <- sink_r.elements) {
          try {
            source_r.elements.get(field) match {
              case Some(source_sub) => connect(sourceInfo, connectCompileOptions, sink_sub, source_sub, context_mod)
              case None => {
                if (connectCompileOptions.connectFieldsMustMatch) {
                  throw MissingFieldException(field)
                }
              }
            }
          } catch {
            case MonoConnectException(message) => throw MonoConnectException(s".$field$message")
          }
        }
      // Handle Record connected to DontCare. Apply the DontCare to individual elements.
      case (sink_r: Record, DontCare) =>
        // For each field, descend with right
        for((field, sink_sub) <- sink_r.elements) {
          try {
            connect(sourceInfo, connectCompileOptions, sink_sub, source, context_mod)
          } catch {
            case MonoConnectException(message) => throw MonoConnectException(s".$field$message")
          }
        }

      // Source is DontCare - it may be connected to anything. It generates a defInvalid for the sink.
      case (sink, DontCare) => pushCommand(DefInvalid(sourceInfo, sink.lref))
      // DontCare as a sink is illegal.
      case (DontCare, _) => throw DontCareCantBeSink
      // Analog is illegal in mono connections.
      case (_: Analog, _:Analog) => throw AnalogMonoConnectionException
      // Analog is illegal in mono connections.
      case (_: Analog, _) => throw AnalogCantBeMonoSink
      // Analog is illegal in mono connections.
      case (_, _: Analog) => throw AnalogCantBeMonoSource
      // Sink and source are different subtypes of data so fail
      case (sink, source) => throw MismatchedException(sink.toString, source.toString)
    }

  // This function (finally) issues the connection operation
  private def issueConnect(sink: Element, source: Element)(implicit sourceInfo: SourceInfo): Unit = {
    // If the source is a DontCare, generate a DefInvalid for the sink,
    //  otherwise, issue a Connect.
    source.topBinding match {
      case b: DontCareBinding => pushCommand(DefInvalid(sourceInfo, sink.lref))
      case _ => pushCommand(Connect(sourceInfo, sink.lref, source.ref))
    }
  }

  // This function checks if element-level connection operation allowed.
  // Then it either issues it or throws the appropriate exception.
  def elemConnect(implicit sourceInfo: SourceInfo, connectCompileOptions: CompileOptions, _sink: Element, _source: Element, context_mod: RawModule): Unit = {
    import BindingDirection.{Internal, Input, Output} // Using extensively so import these
    val sink = reify(_sink)
    val source = reify(_source)
    // If source has no location, assume in context module
    // This can occur if is a literal, unbound will error previously
    val sink_mod: BaseModule   = sink.topBinding.location.getOrElse(throw UnwritableSinkException)
    val source_mod: BaseModule = source.topBinding.location.getOrElse(context_mod)

    val sink_parent = Builder.retrieveParent(sink_mod, context_mod).getOrElse(None)
    val source_parent = Builder.retrieveParent(source_mod, context_mod).getOrElse(None)

    val sink_direction = BindingDirection.from(sink.topBinding, sink.direction)
    val source_direction = BindingDirection.from(source.topBinding, source.direction)

    if (!checkWhenVisibility(sink)) {
      throw SinkEscapedWhenScopeException
    }

    if (!checkWhenVisibility(source)) {
      throw SourceEscapedWhenScopeException
    }

    // CASE: Context is same module that both left node and right node are in
    if( (context_mod == sink_mod) && (context_mod == source_mod) ) {
      ((sink_direction, source_direction): @unchecked) match {
        //    SINK          SOURCE
        //    CURRENT MOD   CURRENT MOD
        case (Output,       _) => issueConnect(sink, source)
        case (Internal,     _) => issueConnect(sink, source)
        case (Input,        _) => throw UnwritableSinkException
      }
    }

    // CASE: Context is same module as sink node and right node is in a child module
    else if((sink_mod == context_mod) && (source_parent == context_mod)) {
      // Thus, right node better be a port node and thus have a direction
      ((sink_direction, source_direction): @unchecked) match {
        //    SINK          SOURCE
        //    CURRENT MOD   CHILD MOD
        case (Internal,     Output) => issueConnect(sink, source)
        case (Internal,     Input)  => issueConnect(sink, source)
        case (Output,       Output) => issueConnect(sink, source)
        case (Output,       Input)  => issueConnect(sink, source)
        case (_,            Internal) => {
          if (!(connectCompileOptions.dontAssumeDirectionality)) {
            issueConnect(sink, source)
          } else {
            throw UnreadableSourceException
          }
        }
        case (Input,        Output) if (!(connectCompileOptions.dontTryConnectionsSwapped)) => issueConnect(source, sink)
        case (Input,        _)    => throw UnwritableSinkException
      }
    }

    // CASE: Context is same module as source node and sink node is in child module
    else if((source_mod == context_mod) && (sink_parent == context_mod)) {
      // Thus, left node better be a port node and thus have a direction
      ((sink_direction, source_direction): @unchecked) match {
        //    SINK          SOURCE
        //    CHILD MOD     CURRENT MOD
        case (Input,        _) => issueConnect(sink, source)
        case (Output,       _) => throw UnwritableSinkException
        case (Internal,     _) => throw UnwritableSinkException
      }
    }

    // CASE: Context is the parent module of both the module containing sink node
    //                                        and the module containing source node
    //   Note: This includes case when sink and source in same module but in parent
    else if((sink_parent == context_mod) && (source_parent == context_mod)) {
      // Thus both nodes must be ports and have a direction
      ((sink_direction, source_direction): @unchecked) match {
        //    SINK          SOURCE
        //    CHILD MOD     CHILD MOD
        case (Input,        Input)  => issueConnect(sink, source)
        case (Input,        Output) => issueConnect(sink, source)
        case (Output,       _)      => throw UnwritableSinkException
        case (_,            Internal) => {
          if (!(connectCompileOptions.dontAssumeDirectionality)) {
            issueConnect(sink, source)
          } else {
            throw UnreadableSourceException
          }
        }
        case (Internal,     _)      => throw UnwritableSinkException
      }
    }

    // Not quite sure where left and right are compared to current module
    // so just error out
    else throw UnknownRelationException
  }
}
