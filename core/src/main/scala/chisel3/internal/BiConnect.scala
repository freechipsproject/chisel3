// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.experimental.dataview.reify
import chisel3.experimental.{Analog, BaseModule, attach}
import chisel3.internal.Builder.pushCommand
import chisel3.internal.firrtl.{Connect, DefInvalid}

import scala.language.experimental.macros
import chisel3.internal.sourceinfo._

/**
* BiConnect.connect executes a bidirectional connection element-wise.
*
* Note that the arguments are left and right (not source and sink) so the
* intent is for the operation to be commutative.
*
* The connect operation will recurse down the left Data (with the right Data).
* An exception will be thrown if a movement through the left cannot be matched
* in the right (or if the right side has extra fields).
*
* See elemConnect for details on how the root connections are issued.
*
*/

private[chisel3] object BiConnect {
  // These are all the possible exceptions that can be thrown.
  // These are from element-level connection
  def BothDriversException =
    BiConnectException(": Both Left and Right are drivers")
  def NeitherDriverException =
    BiConnectException(": Neither Left nor Right is a driver")
  def UnknownDriverException =
    BiConnectException(": Locally unclear whether Left or Right (both internal)")
  def UnknownRelationException =
    BiConnectException(": Left or Right unavailable to current module.")
  // These are when recursing down aggregate types
  def MismatchedVecException =
    BiConnectException(": Left and Right are different length Vecs.")
  def MissingLeftFieldException(field: String) =
    BiConnectException(s".$field: Left Record missing field ($field).")
  def MissingRightFieldException(field: String) =
    BiConnectException(s": Right Record missing field ($field).")
  def MismatchedException(left: String, right: String) =
    BiConnectException(s": Left ($left) and Right ($right) have different types.")
  def AttachAlreadyBulkConnectedException(sourceInfo: SourceInfo) =
    BiConnectException(sourceInfo.makeMessage(": Analog previously bulk connected at " + _))
  def DontCareCantBeSink =
    BiConnectException(": DontCare cannot be a connection sink (LHS)")

  /** This function is what recursively tries to connect a left and right together
  *
  * There is some cleverness in the use of internal try-catch to catch exceptions
  * during the recursive decent and then rethrow them with extra information added.
  * This gives the user a 'path' to where in the connections things went wrong.
  */
  def connect(sourceInfo: SourceInfo, connectCompileOptions: CompileOptions, left: Data, right: Data, context_mod: RawModule): Unit = {
    (left, right) match {
      // Handle element case (root case)
      case (left_a: Analog, right_a: Analog) =>
        try {
          markAnalogConnected(sourceInfo, left_a, context_mod)
          markAnalogConnected(sourceInfo, right_a, context_mod)
        } catch {  // convert attach exceptions to BiConnectExceptions
          case attach.AttachException(message) => throw BiConnectException(message)
        }
        attach.impl(Seq(left_a, right_a), context_mod)(sourceInfo)
      case (left_a: Analog, DontCare) =>
        try {
          markAnalogConnected(sourceInfo, left_a, context_mod)
        } catch {  // convert attach exceptions to BiConnectExceptions
          case attach.AttachException(message) => throw BiConnectException(message)
        }
        pushCommand(DefInvalid(sourceInfo, left_a.lref))
      case (DontCare, right_a: Analog) => connect(sourceInfo, connectCompileOptions, right, left, context_mod)
      case (left_e: Element, right_e: Element) => {
        elemConnect(sourceInfo, connectCompileOptions, left_e, right_e, context_mod)
        // TODO(twigg): Verify the element-level classes are connectable
      }
      // Handle Vec case
      case (left_v: Vec[Data@unchecked], right_v: Vec[Data@unchecked]) => {
        if (left_v.length != right_v.length) {
          throw MismatchedVecException
        }
        for (idx <- 0 until left_v.length) {
          try {
            implicit val compileOptions = connectCompileOptions
            connect(sourceInfo, connectCompileOptions, left_v(idx), right_v(idx), context_mod)
          } catch {
            case BiConnectException(message) => throw BiConnectException(s"($idx)$message")
          }
        }
      }
      // Handle Vec connected to DontCare
      case (left_v: Vec[Data@unchecked], DontCare) => {
        for (idx <- 0 until left_v.length) {
          try {
            implicit val compileOptions = connectCompileOptions
            connect(sourceInfo, connectCompileOptions, left_v(idx), right, context_mod)
          } catch {
            case BiConnectException(message) => throw BiConnectException(s"($idx)$message")
          }
        }
      }
      // Handle DontCare connected to Vec
      case (DontCare, right_v: Vec[Data@unchecked]) => {
        for (idx <- 0 until right_v.length) {
          try {
            implicit val compileOptions = connectCompileOptions
            connect(sourceInfo, connectCompileOptions, left, right_v(idx), context_mod)
          } catch {
            case BiConnectException(message) => throw BiConnectException(s"($idx)$message")
          }
        }
      }
      // Handle Records defined in Chisel._ code by emitting a FIRRTL partial connect
      case pair @ (left_r: Record, right_r: Record) =>
        val notStrict =
          Seq(left_r.compileOptions, right_r.compileOptions).contains(ExplicitCompileOptions.NotStrict)
        if (notStrict) {
          // chisel3 <> is commutative but FIRRTL <- is not
          val flipped = {
            import ActualDirection._
            // Everything is flipped when it's the port of a child
            val childPort = left_r._parent.get != context_mod
            val isFlipped = Seq(Bidirectional(Flipped), Input).contains(left_r.direction)
            isFlipped ^ childPort
          }
          val (newLeft, newRight) = if (flipped) pair.swap else pair
          newLeft.bulkConnect(newRight)(sourceInfo, ExplicitCompileOptions.NotStrict)
        } else {
          recordConnect(sourceInfo, connectCompileOptions, left_r, right_r, context_mod)
        }

      // Handle Records connected to DontCare (change to NotStrict)
      case (left_r: Record, DontCare) =>
        left_r.compileOptions match {
          case ExplicitCompileOptions.NotStrict =>
            left.bulkConnect(right)(sourceInfo, ExplicitCompileOptions.NotStrict)
          case _ =>
            // For each field in left, descend with right
            for ((field, left_sub) <- left_r.elements) {
              try {
                connect(sourceInfo, connectCompileOptions, left_sub, right, context_mod)
              } catch {
                case BiConnectException(message) => throw BiConnectException(s".$field$message")
              }
            }
        }
      case (DontCare, right_r: Record) =>
        right_r.compileOptions match {
          case ExplicitCompileOptions.NotStrict =>
            left.bulkConnect(right)(sourceInfo, ExplicitCompileOptions.NotStrict)
          case _ =>
            // For each field in left, descend with right
            for ((field, right_sub) <- right_r.elements) {
              try {
                connect(sourceInfo, connectCompileOptions, left, right_sub, context_mod)
              } catch {
                case BiConnectException(message) => throw BiConnectException(s".$field$message")
              }
            }
        }

      // Left and right are different subtypes of Data so fail
      case (left, right) => throw MismatchedException(left.toString, right.toString)
    }
  }

  // Do connection of two Records
  def recordConnect(sourceInfo: SourceInfo,
                    connectCompileOptions: CompileOptions,
                    left_r: Record,
                    right_r: Record,
                    context_mod: RawModule): Unit = {
    // Verify right has no extra fields that left doesn't have
    for((field, right_sub) <- right_r.elements) {
      if(!left_r.elements.isDefinedAt(field)) {
        if (connectCompileOptions.connectFieldsMustMatch) {
          throw MissingLeftFieldException(field)
        }
      }
    }
    // For each field in left, descend with right
    for((field, left_sub) <- left_r.elements) {
      try {
        right_r.elements.get(field) match {
          case Some(right_sub) => connect(sourceInfo, connectCompileOptions, left_sub, right_sub, context_mod)
          case None => {
            if (connectCompileOptions.connectFieldsMustMatch) {
              throw MissingRightFieldException(field)
            }
          }
        }
      } catch {
        case BiConnectException(message) => throw BiConnectException(s".$field$message")
      }
    }
  }


  // These functions (finally) issue the connection operation
  // Issue with right as sink, left as source
  private def issueConnectL2R(left: Element, right: Element)(implicit sourceInfo: SourceInfo): Unit = {
    // Source and sink are ambiguous in the case of a Bi/Bulk Connect (<>).
    // If either is a DontCareBinding, just issue a DefInvalid for the other,
    //  otherwise, issue a Connect.
    (left.topBinding, right.topBinding) match {
      case (lb: DontCareBinding, _) => pushCommand(DefInvalid(sourceInfo, right.lref))
      case (_, rb: DontCareBinding) => pushCommand(DefInvalid(sourceInfo, left.lref))
      case (_, _) => pushCommand(Connect(sourceInfo, right.lref, left.ref))
    }
  }
  // Issue with left as sink, right as source
  private def issueConnectR2L(left: Element, right: Element)(implicit sourceInfo: SourceInfo): Unit = {
    // Source and sink are ambiguous in the case of a Bi/Bulk Connect (<>).
    // If either is a DontCareBinding, just issue a DefInvalid for the other,
    //  otherwise, issue a Connect.
    (left.topBinding, right.topBinding) match {
      case (lb: DontCareBinding, _) => pushCommand(DefInvalid(sourceInfo, right.lref))
      case (_, rb: DontCareBinding) => pushCommand(DefInvalid(sourceInfo, left.lref))
      case (_, _) => pushCommand(Connect(sourceInfo, left.lref, right.ref))
    }
  }

  // This function checks if element-level connection operation allowed.
  // Then it either issues it or throws the appropriate exception.
  def elemConnect(implicit sourceInfo: SourceInfo, connectCompileOptions: CompileOptions, _left: Element, _right: Element, context_mod: RawModule): Unit = {
    import BindingDirection.{Internal, Input, Output} // Using extensively so import these
    val left = reify(_left)
    val right = reify(_right)
    // If left or right have no location, assume in context module
    // This can occur if one of them is a literal, unbound will error previously
    val left_mod: BaseModule  = left.topBinding.location.getOrElse(context_mod)
    val right_mod: BaseModule = right.topBinding.location.getOrElse(context_mod)

    val left_parent = Builder.retrieveParent(left_mod, context_mod).getOrElse(None)
    val right_parent = Builder.retrieveParent(right_mod, context_mod).getOrElse(None)

    val left_direction = BindingDirection.from(left.topBinding, left.direction)
    val right_direction = BindingDirection.from(right.topBinding, right.direction)

    // CASE: Context is same module as left node and right node is in a child module
    if((left_mod == context_mod) && (right_parent == context_mod)) {
      // Thus, right node better be a port node and thus have a direction hint
      ((left_direction, right_direction): @unchecked) match {
        //    CURRENT MOD   CHILD MOD
        case (Input,        Input)  => issueConnectL2R(left, right)
        case (Internal,     Input)  => issueConnectL2R(left, right)

        case (Output,       Output) => issueConnectR2L(left, right)
        case (Internal,     Output) => issueConnectR2L(left, right)

        case (Input,        Output) => throw BothDriversException
        case (Output,       Input)  => throw NeitherDriverException
        case (_,            Internal) => throw UnknownRelationException
      }
    }

    // CASE: Context is same module as right node and left node is in child module
    else if((right_mod == context_mod) && (left_parent == context_mod)) {
      // Thus, left node better be a port node and thus have a direction hint
      ((left_direction, right_direction): @unchecked) match {
        //    CHILD MOD     CURRENT MOD
        case (Input,        Input)  => issueConnectR2L(left, right)
        case (Input,        Internal)         => issueConnectR2L(left, right)

        case (Output,       Output) => issueConnectL2R(left, right)
        case (Output,       Internal)         => issueConnectL2R(left, right)

        case (Input,        Output) => throw NeitherDriverException
        case (Output,       Input)  => throw BothDriversException
        case (Internal,     _)      => throw UnknownRelationException
      }
    }

    // CASE: Context is same module that both left node and right node are in
    else if( (context_mod == left_mod) && (context_mod == right_mod) ) {
      ((left_direction, right_direction): @unchecked) match {
        //    CURRENT MOD   CURRENT MOD
        case (Input,        Output) => issueConnectL2R(left, right)
        case (Input,        Internal) => issueConnectL2R(left, right)
        case (Internal,     Output) => issueConnectL2R(left, right)

        case (Output,       Input)  => issueConnectR2L(left, right)
        case (Output,       Internal) => issueConnectR2L(left, right)
        case (Internal,     Input)  => issueConnectR2L(left, right)

        case (Input,        Input)  => throw BothDriversException
        case (Output,       Output) => throw BothDriversException
        case (Internal,     Internal) => {
          if (connectCompileOptions.dontAssumeDirectionality) {
            throw UnknownDriverException
          } else {
            issueConnectR2L(left, right)
          }
        }
      }
    }

    // CASE: Context is the parent module of both the module containing left node
    //                                        and the module containing right node
    //   Note: This includes case when left and right in same module but in parent
    else if((left_parent == context_mod) && (right_parent == context_mod)) {
      // Thus both nodes must be ports and have a direction hint
      ((left_direction, right_direction): @unchecked) match {
        //    CHILD MOD     CHILD MOD
        case (Input,        Output) => issueConnectR2L(left, right)
        case (Output,       Input)  => issueConnectL2R(left, right)

        case (Input,        Input)  => throw NeitherDriverException
        case (Output,       Output) => throw BothDriversException
        case (_, Internal)          =>
          if (connectCompileOptions.dontAssumeDirectionality) {
            throw UnknownRelationException
          } else {
            issueConnectR2L(left, right)
          }
        case (Internal, _)          =>
          if (connectCompileOptions.dontAssumeDirectionality) {
            throw UnknownRelationException
          } else {
            issueConnectR2L(left, right)
          }
      }
    }

    // Not quite sure where left and right are compared to current module
    // so just error out
    else throw UnknownRelationException
  }

  // This function checks if analog element-level attaching is allowed, then marks the Analog as connected
  def markAnalogConnected(implicit sourceInfo: SourceInfo, analog: Analog, contextModule: RawModule): Unit = {
    analog.biConnectLocs.get(contextModule) match {
      case Some(sl) => throw AttachAlreadyBulkConnectedException(sl)
      case None => // Do nothing
    }
    // Mark bulk connected
    analog.biConnectLocs(contextModule) = sourceInfo
  }
}
