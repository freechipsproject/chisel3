// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import scala.util.DynamicVariable
import scala.collection.mutable.ArrayBuffer
import chisel3._
import chisel3.experimental._
import chisel3.internal.firrtl._
import chisel3.internal.naming._
import _root_.firrtl.annotations.{CircuitName, ComponentName, IsMember, ModuleName, Named, ReferenceTarget}
import _root_.firrtl.annotations.AnnotationUtils.validComponentName
import _root_.firrtl.AnnotationSeq
import chisel3.internal.Builder.Prefix
import logger.LazyLogging

import scala.collection.mutable

private[chisel3] class Namespace(keywords: Set[String]) {
  private val names = collection.mutable.HashMap[String, Long]()
  for (keyword <- keywords)
    names(keyword) = 1

  private def rename(n: String): String = {
    val index = names(n)
    val tryName = s"${n}_${index}"
    names(n) = index + 1
    if (this contains tryName) rename(n) else tryName
  }

  private def sanitize(s: String, leadingDigitOk: Boolean = false): String = {
    // TODO what character set does FIRRTL truly support? using ANSI C for now
    def legalStart(c: Char) = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
    def legal(c: Char) = legalStart(c) || (c >= '0' && c <= '9')
    val res = s filter legal
    val headOk = (!res.isEmpty) && (leadingDigitOk || legalStart(res.head))
    if (headOk) res else s"_$res"
  }

  def contains(elem: String): Boolean = names.contains(elem)

  // leadingDigitOk is for use in fields of Records
  def name(elem: String, leadingDigitOk: Boolean = false): String = {
    val sanitized = sanitize(elem, leadingDigitOk)
    if (this contains sanitized) {
      name(rename(sanitized))
    } else {
      names(sanitized) = 1
      sanitized
    }
  }
}

private[chisel3] object Namespace {
  /** Constructs an empty Namespace */
  def empty: Namespace = new Namespace(Set.empty[String])
}

private[chisel3] class IdGen {
  private var counter = -1L
  def next: Long = {
    counter += 1
    counter
  }
  def value: Long = counter
}

/** Public API to access Node/Signal names.
  * currently, the node's name, the full path name, and references to its parent Module and component.
  * These are only valid once the design has been elaborated, and should not be used during its construction.
  */
trait InstanceId {
  def instanceName: String
  def pathName: String
  def parentPathName: String
  def parentModName: String
  /** Returns a FIRRTL Named that refers to this object in the elaborated hardware graph */
  def toNamed: Named
  /** Returns a FIRRTL IsMember that refers to this object in the elaborated hardware graph */
  def toTarget: IsMember
  /** Returns a FIRRTL IsMember that refers to the absolute path to this object in the elaborated hardware graph */
  def toAbsoluteTarget: IsMember
}

private[chisel3] trait HasId extends InstanceId {
  private[chisel3] def _onModuleClose: Unit = {}
  private[chisel3] var _parent: Option[BaseModule] = Builder.currentModule

  private[chisel3] val _id: Long = Builder.idGen.next

  // TODO: remove this, but its removal seems to cause a nasty Scala compiler crash.
  override def hashCode: Int = super.hashCode()
  override def equals(that: Any): Boolean = super.equals(that)

  // Contains suggested seed (user-decided seed)
  private var suggested_seed: Option[String] = None

  // Contains the seed computed automatically by the compiler plugin
  private var auto_seed: Option[String] = None

  // Prefix at time when this class is constructed
  private val construction_prefix: Prefix = Builder.getPrefix()

  // Prefix when the latest [[suggestSeed]] or [[autoSeed]] is called
  private var prefix_seed: Prefix = Nil

  // Post-seed hooks called to carry the suggested seeds to other candidates as needed
  private val suggest_postseed_hooks = scala.collection.mutable.ListBuffer.empty[String=>Unit]

  // Post-seed hooks called to carry the auto seeds to other candidates as needed
  private val auto_postseed_hooks = scala.collection.mutable.ListBuffer.empty[String=>Unit]

  /** Takes the last seed suggested. Multiple calls to this function will take the last given seed, unless
    * this HasId is a module port (see overridden method in Data.scala).
    *
    * If the final computed name conflicts with the final name of another signal, the final name may get uniquified by
    * appending a digit at the end of the name.
    *
    * Is a lower priority than [[suggestName]], in that regardless of whether [[autoSeed]]
    * was called, [[suggestName]] will always take precedence if it was called.
 *
    * @param seed Seed for the name of this component
    * @return this object
    */
  private[chisel3] def autoSeed(seed: String): this.type = forceAutoSeed(seed)
  // Bypass the overridden behavior of autoSeed in [[Data]], apply autoSeed even to ports
  private[chisel3] def forceAutoSeed(seed: String): this.type = {
    auto_seed = Some(seed)
    for(hook <- auto_postseed_hooks) { hook(seed) }
    prefix_seed = Builder.getPrefix()
    this
  }

  /** Takes the first seed suggested. Multiple calls to this function will be ignored.
    * If the final computed name conflicts with another name, it may get uniquified by appending
    * a digit at the end.
    *
    * Is a higher priority than [[autoSeed]], in that regardless of whether [[autoSeed]]
    * was called, [[suggestName]] will always take precedence.
 *
    * @param seed The seed for the name of this component
    * @return this object
    */
  def suggestName(seed: =>String): this.type = {
    if(suggested_seed.isEmpty) suggested_seed = Some(seed)
    prefix_seed = Builder.getPrefix()
    for(hook <- suggest_postseed_hooks) { hook(seed) }
    this
  }

  /** Computes the name of this HasId, if one exists
    * @param defaultPrefix Optionally provide a default prefix for computing the name
    * @param defaultSeed Optionally provide default seed for computing the name
    * @return the name, if it can be computed
    */
  def computeName(defaultPrefix: Option[String], defaultSeed: Option[String]): Option[String] = {
    /** Computes a name of this signal, given the seed and prefix
      * @param seed
      * @param prefix
      * @return
      */
    def buildName(seed: String, prefix: Prefix): String = {
      val builder = new StringBuilder()
      prefix.foreach(builder ++= _ + "_")
      builder ++= seed
      builder.toString
    }

    if (hasSeed) {
      Some(buildName(seedOpt.get, prefix_seed.reverse))
    } else {
      defaultSeed.map { default =>
        defaultPrefix match {
          case Some(p) => buildName(default, p :: construction_prefix.reverse)
          case None => buildName(default, construction_prefix.reverse)
        }
      }
    }
  }

  /** This resolves the precedence of [[autoSeed]] and [[suggestName]]
 *
    * @return the current calculation of a name, if it exists
    */
  private[chisel3] def seedOpt: Option[String] = suggested_seed.orElse(auto_seed)

  /** @return Whether either autoName or suggestName has been called */
  def hasSeed: Boolean = seedOpt.isDefined

  private[chisel3] def hasAutoSeed: Boolean = auto_seed.isDefined

  private[chisel3] def addSuggestPostnameHook(hook: String=>Unit): Unit = suggest_postseed_hooks += hook
  private[chisel3] def addAutoPostnameHook(hook: String=>Unit): Unit = auto_postseed_hooks += hook

  // Uses a namespace to convert suggestion into a true name
  // Will not do any naming if the reference already assigned.
  // (e.g. tried to suggest a name to part of a Record)
  private[chisel3] def forceName(prefix: Option[String], default: =>String, namespace: Namespace): Unit =
    if(_ref.isEmpty) {
      val candidate_name = computeName(prefix, Some(default)).get
      val available_name = namespace.name(candidate_name)
      setRef(Ref(available_name))
    }

  private var _ref: Option[Arg] = None
  private[chisel3] def setRef(imm: Arg): Unit = setRef(imm, false)
  private[chisel3] def setRef(imm: Arg, force: Boolean): Unit = {
    if (_ref.isEmpty || force) {
      _ref = Some(imm)
    }
  }
  private[chisel3] def setRef(parent: HasId, name: String): Unit = setRef(Slot(Node(parent), name))
  private[chisel3] def setRef(parent: HasId, index: Int): Unit = setRef(Index(Node(parent), ILit(index)))
  private[chisel3] def setRef(parent: HasId, index: UInt): Unit = setRef(Index(Node(parent), index.ref))
  private[chisel3] def getRef: Arg = _ref.get
  private[chisel3] def getOptionRef: Option[Arg] = _ref

  // Implementation of public methods.
  def instanceName: String = _parent match {
    case Some(p) => p._component match {
      case Some(c) => _ref match {
        case Some(arg) => arg fullName c
        case None => computeName(None, None).get
      }
      case None => throwException("signalName/pathName should be called after circuit elaboration")
    }
    case None => throwException("this cannot happen")
  }
  def pathName: String = _parent match {
    case None => instanceName
    case Some(p) => s"${p.pathName}.$instanceName"
  }
  def parentPathName: String = _parent match {
    case Some(p) => p.pathName
    case None => throwException(s"$instanceName doesn't have a parent")
  }
  def parentModName: String = _parent match {
    case Some(p) => p.name
    case None => throwException(s"$instanceName doesn't have a parent")
  }
  // TODO Should this be public?
  protected def circuitName: String = _parent match {
    case None => instanceName
    case Some(p) => p.circuitName
  }

  private[chisel3] def getPublicFields(rootClass: Class[_]): Seq[java.lang.reflect.Method] = {
    // Suggest names to nodes using runtime reflection
    def getValNames(c: Class[_]): Set[String] = {
      if (c == rootClass) {
        Set()
      } else {
        getValNames(c.getSuperclass) ++ c.getDeclaredFields.map(_.getName)
      }
    }
    val valNames = getValNames(this.getClass)
    def isPublicVal(m: java.lang.reflect.Method) = {
      val noParameters = m.getParameterTypes.isEmpty
      val aVal = valNames.contains(m.getName)
      val notAssignable = !m.getDeclaringClass.isAssignableFrom(rootClass)
      val notWeirdVal = !m.getName.contains('$')
      noParameters && aVal && notAssignable && notWeirdVal
    }
    this.getClass.getMethods.filter(isPublicVal).sortWith(_.getName < _.getName)
  }
}
/** Holds the implementation of toNamed for Data and MemBase */
private[chisel3] trait NamedComponent extends HasId {
  /** Returns a FIRRTL ComponentName that references this object
    * @note Should not be called until circuit elaboration is complete
    */
  final def toNamed: ComponentName =
    ComponentName(this.instanceName, ModuleName(this.parentModName, CircuitName(this.circuitName)))

  /** Returns a FIRRTL ReferenceTarget that references this object
    * @note Should not be called until circuit elaboration is complete
    */
  final def toTarget: ReferenceTarget = {
    val name = this.instanceName
    if (!validComponentName(name)) throwException(s"Illegal component name: $name (note: literals are illegal)")
    import _root_.firrtl.annotations.{Target, TargetToken}
    Target.toTargetTokens(name).toList match {
      case TargetToken.Ref(r) :: components => ReferenceTarget(this.circuitName, this.parentModName, Nil, r, components)
      case other =>
        throw _root_.firrtl.annotations.Target.NamedException(s"Cannot convert $name into [[ReferenceTarget]]: $other")
    }
  }

  final def toAbsoluteTarget: ReferenceTarget = {
    val localTarget = toTarget
    _parent match {
      case Some(parent) => parent.toAbsoluteTarget.ref(localTarget.ref).copy(component = localTarget.component)
      case None => localTarget
    }
  }
}

// Mutable global state for chisel that can appear outside a Builder context
private[chisel3] class ChiselContext() {
  val idGen = new IdGen

  // Record the Bundle instance, class name, method name, and reverse stack trace position of open Bundles
  val bundleStack: ArrayBuffer[(Bundle, String, String, Int)] = ArrayBuffer()

  // Records the different prefixes which have been scoped at this point in time
  var prefixStack: Prefix = Nil
}

private[chisel3] class DynamicContext(val annotationSeq: AnnotationSeq) {
  val globalNamespace = Namespace.empty
  val components = ArrayBuffer[Component]()
  val annotations = ArrayBuffer[ChiselAnnotation]()
  var currentModule: Option[BaseModule] = None
  // This is only used for testing, it can be removed if the plugin becomes mandatory
  var allowReflectiveAutoCloneType = true

  /** Contains a mapping from a elaborated module to their aspect
    * Set by [[ModuleAspect]]
    */
  val aspectModule: mutable.HashMap[BaseModule, BaseModule] = mutable.HashMap.empty[BaseModule, BaseModule]

  // Set by object Module.apply before calling class Module constructor
  // Used to distinguish between no Module() wrapping, multiple wrappings, and rewrapping
  var readyForModuleConstr: Boolean = false
  var whenStack: List[WhenContext] = Nil
  var currentClock: Option[Clock] = None
  var currentReset: Option[Reset] = None
  val errors = new ErrorLog
  val namingStack = new NamingStack
}

private[chisel3] object Builder extends LazyLogging {

  // Represents the current state of the prefixes given
  type Prefix = List[String]

  // All global mutable state must be referenced via dynamicContextVar!!
  private val dynamicContextVar = new DynamicVariable[Option[DynamicContext]](None)
  private def dynamicContext: DynamicContext = {
    require(dynamicContextVar.value.isDefined, "must be inside Builder context")
    dynamicContextVar.value.get
  }

  // Ensure we have a thread-specific ChiselContext
  private val chiselContext = new ThreadLocal[ChiselContext]{
    override def initialValue: ChiselContext = {
      new ChiselContext
    }
  }

  // Initialize any singleton objects before user code inadvertently inherits them.
  private def initializeSingletons(): Unit = {
    // This used to contain:
    //    val dummy = core.DontCare
    //  but this would occasionally produce hangs due to static initialization deadlock
    //  when Builder initialization collided with chisel3.package initialization of the DontCare value.
    // See:
    //  http://ternarysearch.blogspot.com/2013/07/static-initialization-deadlock.html
    //  https://bugs.openjdk.java.net/browse/JDK-8037567
    //  https://stackoverflow.com/questions/28631656/runnable-thread-state-but-in-object-wait
  }

  def namingStackOption: Option[NamingStack] = dynamicContextVar.value.map(_.namingStack)

  def idGen: IdGen = chiselContext.get.idGen

  def globalNamespace: Namespace = dynamicContext.globalNamespace
  def components: ArrayBuffer[Component] = dynamicContext.components
  def annotations: ArrayBuffer[ChiselAnnotation] = dynamicContext.annotations
  def annotationSeq: AnnotationSeq = dynamicContext.annotationSeq
  def namingStack: NamingStack = dynamicContext.namingStack

  // Puts a prefix string onto the prefix stack
  def pushPrefix(d: String): Unit = {
    val context = chiselContext.get()
    context.prefixStack = d :: context.prefixStack
  }

  /** Pushes the current name of a data onto the prefix stack
    *
    * @param d data to derive prefix from
    * @return whether anything was pushed to the stack
    */
  def pushPrefix(d: HasId): Boolean = {
    def buildAggName(id: HasId): Option[String] = {
      def getSubName(field: Data): Option[String] = field.getOptionRef.flatMap {
        case Slot(_, field) => Some(field) // Record
        case Index(_, ILit(n)) => Some(n.toString) // Vec static indexing
        case Index(_, ULit(n, _)) => Some(n.toString) // Vec lit indexing
        case Index(_, _: Node) => None // Vec dynamic indexing
        case ModuleIO(_, n) => Some(n) // BlackBox port
      }
      def map2[A, B](a: Option[A], b: Option[A])(f: (A, A) => B): Option[B] =
        a.flatMap(ax => b.map(f(ax, _)))
      // If the binding is None, this is an illegal connection and later logic will error
      def recData(data: Data): Option[String] = data.binding.flatMap {
        case (_: WireBinding | _: RegBinding | _: MemoryPortBinding | _: OpBinding) => data.seedOpt
        case ChildBinding(parent) => recData(parent).map { p =>
          // And name of the field if we have one, we don't for dynamic indexing of Vecs
          getSubName(data).map(p + "_" + _).getOrElse(p)
        }
        case SampleElementBinding(parent) => recData(parent)
        case PortBinding(mod) if Builder.currentModule.contains(mod) => data.seedOpt
        case PortBinding(mod) => map2(mod.seedOpt, data.seedOpt)(_ + "_" + _)
        case (_: LitBinding | _: DontCareBinding) => None
        case _ => Some("view_") // TODO implement
      }
      id match {
        case d: Data => recData(d)
        case _ => None
      }
    }
    buildAggName(d).map { name =>
      pushPrefix(name)
    }.isDefined
  }

  // Remove a prefix from top of the stack
  def popPrefix(): List[String] = {
    val context = chiselContext.get()
    val tail = context.prefixStack.tail
    context.prefixStack = tail
    tail
  }

  // Removes all prefixes from the prefix stack
  def clearPrefix(): Unit = {
    chiselContext.get().prefixStack = Nil
  }

  // Clears existing prefixes and sets to new prefix stack
  def setPrefix(prefix: Prefix): Unit = {
    chiselContext.get().prefixStack = prefix
  }

  // Returns the prefix stack at this moment
  def getPrefix(): Prefix = chiselContext.get().prefixStack

  def currentModule: Option[BaseModule] = dynamicContextVar.value match {
    case Some(dyanmicContext) => dynamicContext.currentModule
    case _ => None
  }
  def currentModule_=(target: Option[BaseModule]): Unit = {
    dynamicContext.currentModule = target
  }
  def aspectModule(module: BaseModule): Option[BaseModule] = dynamicContextVar.value match {
    case Some(dynamicContext) => dynamicContext.aspectModule.get(module)
    case _ => None
  }
  /** Retrieves the parent of a module based on the elaboration context
   *
   * @param module the module to get the parent of
   * @param context the context the parent should be evaluated in
   * @return the parent of the module provided
   */
  def retrieveParent(module: BaseModule, context: BaseModule): Option[BaseModule] = {
    module._parent match {
      case Some(parentModule) => { //if a parent exists investigate, otherwise return None
        context match {
          case aspect: ModuleAspect => { //if aspect context, do the translation
              Builder.aspectModule(parentModule) match {
                case Some(parentAspect) => Some(parentAspect) //we've found a translation
                case _ => Some(parentModule) //no translation found
              }
          } //otherwise just return our parent
          case _ => Some(parentModule)
        }
      }
      case _ => None
    }
  }
  def addAspect(module: BaseModule, aspect: BaseModule): Unit = {
    dynamicContext.aspectModule += ((module, aspect))
  }
  def forcedModule: BaseModule = currentModule match {
    case Some(module) => module
    case None => throwException(
      "Error: Not in a Module. Likely cause: Missed Module() wrap or bare chisel API call."
      // A bare api call is, e.g. calling Wire() from the scala console).
    )
  }
  def referenceUserModule: RawModule = {
    currentModule match {
      case Some(module: RawModule) =>
        aspectModule(module) match {
          case Some(aspect: RawModule) => aspect
          case other => module
        }
      case _ => throwException(
        "Error: Not in a RawModule. Likely cause: Missed Module() wrap, bare chisel API call, or attempting to construct hardware inside a BlackBox."
        // A bare api call is, e.g. calling Wire() from the scala console).
      )
    }
  }
  def forcedUserModule: RawModule = currentModule match {
    case Some(module: RawModule) => module
    case _ => throwException(
      "Error: Not in a UserModule. Likely cause: Missed Module() wrap, bare chisel API call, or attempting to construct hardware inside a BlackBox."
      // A bare api call is, e.g. calling Wire() from the scala console).
    )
  }
  def readyForModuleConstr: Boolean = dynamicContext.readyForModuleConstr
  def readyForModuleConstr_=(target: Boolean): Unit = {
    dynamicContext.readyForModuleConstr = target
  }

  def whenDepth: Int = dynamicContext.whenStack.length

  def pushWhen(wc: WhenContext): Unit = {
    dynamicContext.whenStack = wc :: dynamicContext.whenStack
  }

  def popWhen(): WhenContext = {
    val lastWhen = dynamicContext.whenStack.head
    dynamicContext.whenStack = dynamicContext.whenStack.tail
    lastWhen
  }

  def whenStack: List[WhenContext] = dynamicContext.whenStack
  def whenStack_=(s: List[WhenContext]): Unit = {
    dynamicContext.whenStack = s
  }

  def currentWhen(): Option[WhenContext] = dynamicContext.whenStack.headOption

  def currentClock: Option[Clock] = dynamicContext.currentClock
  def currentClock_=(newClock: Option[Clock]): Unit = {
    dynamicContext.currentClock = newClock
  }

  def currentReset: Option[Reset] = dynamicContext.currentReset
  def currentReset_=(newReset: Option[Reset]): Unit = {
    dynamicContext.currentReset = newReset
  }

  // This should only be used for testing, must be true outside of Builder context
  def allowReflectiveAutoCloneType: Boolean = {
    dynamicContextVar.value
                     .map(_.allowReflectiveAutoCloneType)
                     .getOrElse(true)
  }
  def allowReflectiveAutoCloneType_=(value: Boolean): Unit = {
    dynamicContext.allowReflectiveAutoCloneType = value
  }

  def forcedClock: Clock = currentClock.getOrElse(
    throwException("Error: No implicit clock.")
  )
  def forcedReset: Reset = currentReset.getOrElse(
    throwException("Error: No implicit reset.")
  )

  // TODO(twigg): Ideally, binding checks and new bindings would all occur here
  // However, rest of frontend can't support this yet.
  def pushCommand[T <: Command](c: T): T = {
    forcedUserModule.addCommand(c)
    c
  }
  def pushOp[T <: Data](cmd: DefPrim[T]): T = {
    // Bind each element of the returned Data to being a Op
    cmd.id.bind(OpBinding(forcedUserModule, currentWhen()))
    pushCommand(cmd).id
  }

  // Called when Bundle construction begins, used to record a stack of open Bundle constructors to
  // record candidates for Bundle autoclonetype. This is a best-effort guess.
  // Returns the current stack of open Bundles
  // Note: elt will NOT have finished construction, its elements cannot be accessed
  def updateBundleStack(elt: Bundle): Seq[Bundle] = {
    val stackElts = Thread.currentThread().getStackTrace()
        .reverse  // so stack frame numbers are deterministic across calls
        .dropRight(2)  // discard Thread.getStackTrace and updateBundleStack

    // Determine where we are in the Bundle stack
    val eltClassName = elt.getClass.getName
    val eltStackPos = stackElts.map(_.getClassName).lastIndexOf(eltClassName)

    // Prune the existing Bundle stack of closed Bundles
    // If we know where we are in the stack, discard frames above that
    val stackEltsTop = if (eltStackPos >= 0) eltStackPos else stackElts.size
    val pruneLength = chiselContext.get.bundleStack.reverse.prefixLength { case (_, cname, mname, pos) =>
      pos >= stackEltsTop || stackElts(pos).getClassName != cname || stackElts(pos).getMethodName != mname
    }
    chiselContext.get.bundleStack.trimEnd(pruneLength)

    // Return the stack state before adding the most recent bundle
    val lastStack = chiselContext.get.bundleStack.map(_._1).toSeq

    // Append the current Bundle to the stack, if it's on the stack trace
    if (eltStackPos >= 0) {
      val stackElt = stackElts(eltStackPos)
      chiselContext.get.bundleStack.append((elt, eltClassName, stackElt.getMethodName, eltStackPos))
    }
    // Otherwise discard the stack frame, this shouldn't fail noisily

    lastStack
  }

  /** Recursively suggests names to supported "container" classes
    * Arbitrary nestings of supported classes are allowed so long as the
    * innermost element is of type HasId
    * (Note: Map is Iterable[Tuple2[_,_]] and thus excluded)
    */
  def nameRecursively(prefix: String, nameMe: Any, namer: (HasId, String) => Unit): Unit = nameMe match {
    case (id: HasId) => namer(id, prefix)
    case Some(elt) => nameRecursively(prefix, elt, namer)
    case (iter: Iterable[_]) if iter.hasDefiniteSize =>
      for ((elt, i) <- iter.zipWithIndex) {
        nameRecursively(s"${prefix}_${i}", elt, namer)
      }
    case _ => // Do nothing
  }

  def errors: ErrorLog = dynamicContext.errors
  def error(m: => String): Unit = {
    if (dynamicContextVar.value.isDefined) {
      errors.error(m)
    } else {
      throwException(m)
    }
  }
  def warning(m: => String): Unit = if (dynamicContextVar.value.isDefined) errors.warning(m)
  def deprecated(m: => String, location: Option[String] = None): Unit =
    if (dynamicContextVar.value.isDefined) errors.deprecated(m, location)

  /** Record an exception as an error, and throw it.
    *
    * @param m exception message
    */
  @throws(classOf[ChiselException])
  def exception(m: => String): Nothing = {
    error(m)
    throwException(m)
  }

  def getScalaMajorVersion: Int = {
    val "2" :: major :: _ :: Nil = chisel3.BuildInfo.scalaVersion.split("\\.").toList
    major.toInt
  }

  def checkScalaVersion(): Unit = {
    if (getScalaMajorVersion == 11) {
      val url = _root_.firrtl.stage.transforms.CheckScalaVersion.migrationDocumentLink
      val msg = s"Chisel 3.4 is the last version that will support Scala 2.11. " +
                s"Please upgrade to Scala 2.12. See $url"
      deprecated(msg, Some(""))
    }
  }

  private [chisel3] def build[T <: RawModule](f: => T, dynamicContext: DynamicContext): (Circuit, T) = {
    dynamicContextVar.withValue(Some(dynamicContext)) {
      checkScalaVersion()
      logger.warn("Elaborating design...")
      val mod = f
      mod.forceName(None, mod.name, globalNamespace)
      errors.checkpoint(logger)
      logger.warn("Done elaborating.")

      (Circuit(components.last.name, components.toSeq, annotations.toSeq), mod)
    }
  }
  initializeSingletons()
}

/** Allows public access to the naming stack in Builder / DynamicContext, and handles invocations
  * outside a Builder context.
  * Necessary because naming macros expand in user code and don't have access into private[chisel3]
  * objects.
  */
object DynamicNamingStack {
  def pushContext(): NamingContextInterface = {
    Builder.namingStackOption match {
      case Some(namingStack) => namingStack.pushContext()
      case None => DummyNamer
    }
  }

  def popReturnContext[T <: Any](prefixRef: T, until: NamingContextInterface): T = {
    until match {
      case DummyNamer =>
        require(Builder.namingStackOption.isEmpty,
          "Builder context must remain stable throughout a chiselName-annotated function invocation")
      case context: NamingContext =>
        require(Builder.namingStackOption.isDefined,
          "Builder context must remain stable throughout a chiselName-annotated function invocation")
        Builder.namingStackOption.get.popContext(prefixRef, context)
    }
    prefixRef
  }

  def length() : Int = Builder.namingStackOption.get.length
}

/** Casts BigInt to Int, issuing an error when the input isn't representable. */
private[chisel3] object castToInt {
  def apply(x: BigInt, msg: String): Int = {
    val res = x.toInt
    require(x == res, s"$msg $x is too large to be represented as Int")
    res
  }
}
