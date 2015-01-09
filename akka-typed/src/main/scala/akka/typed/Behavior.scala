/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.typed

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import akka.actor.OneForOneStrategy
import scala.annotation.tailrec
import scala.collection.immutable

/**
 * The behavior of an actor defines how it reacts to the messages that it
 * receives. The message may either be of the type that the Actor declares
 * and which is part of the [[ActorRef]] signature, or it may be a system
 * [[Signal]] that expresses a lifecycle event of either this actor or one of
 * its child actors.
 *
 * Behaviors can be formulated in a number of different ways, either by
 * creating a derived class or by employing factory methods like
 * [[Behavior.Simple]], [[Behavior.Static]], [[Behavior.Full]] etc.
 */
abstract class Behavior[T] { self ⇒
  def management(ctx: ActorContext[T], msg: Signal): Behavior[T]
  def message(ctx: ActorContext[T], msg: T): Behavior[T]

  def narrow[U <: T]: Behavior[U] = this.asInstanceOf[Behavior[U]]

  def widen[U >: T](matcher: PartialFunction[U, T]): Behavior[U] =
    new Behavior[U] { // TODO: optimize allocation-wise
      private def postProcess(ctx: ActorContext[U], behv: Behavior[T]): Behavior[U] =
        Behavior.canonicalize(ctx.asInstanceOf[ActorContext[T]], behv, self).widen(matcher)
      override def management(ctx: ActorContext[U], msg: Signal): Behavior[U] =
        postProcess(ctx, self.management(ctx.asInstanceOf[ActorContext[T]], msg))
      override def message(ctx: ActorContext[U], msg: U): Behavior[U] =
        if (matcher.isDefinedAt(msg))
          postProcess(ctx, self.message(ctx.asInstanceOf[ActorContext[T]], matcher(msg)))
        else Behavior.Unhandled
      override def toString: String = s"${self.toString}.widen(${LN.forClass(matcher.getClass)})"
    }
}

/*
 * FIXME
 * 
 * Closing over ActorContext makes a Behavior immobile: it cannot be moved to
 * another context and executed there, and therefore it cannot be replicated or
 * forked either.
 */

// FIXME to be replaced by extension?
private object LN extends akka.util.LineNumbers

/**
 * System signals are notifications that are generated by the system and
 * delivered to the Actor behavior in a reliable fashion (i.e. they are
 * guaranteed to arrive in contrast to the at-most-once semantics of normal
 * Actor messages).
 */
sealed trait Signal
/**
 * Lifecycle signal that is fired upon creation of the Actor. This will be the
 * first message that the actor processes.
 */
final case object PreStart extends Signal
/**
 * Lifecycle signal that is fired upon restart of the Actor before replacing
 * the behavior with the fresh one (i.e. this signal is received within the
 * behavior that failed).
 */
final case class PreRestart(failure: Throwable) extends Signal
/**
 * Lifecycle signal that is fired upon restart of the Actor after replacing
 * the behavior with the fresh one (i.e. this signal is received within the
 * fresh replacement behavior).
 */
final case class PostRestart(failure: Throwable) extends Signal
/**
 * Lifecycle signal that is fired after this actor and all its child actors
 * (transitively) have terminated. The [[Terminated]] signal is only sent to
 * registered watchers after this signal has been processed.
 */
final case object PostStop extends Signal
/**
 * Lifecycle signal that is fired when a direct child actor fails. The child
 * actor will be suspended until its fate has been decided. The decision is
 * communicated by returning the next behavior wrapped in one of
 * [[Failed$.Resume]], [[Failed$.Restart]], [[Failed$.Stop]]
 * or [[Failed$.Escalate]]. If this is not
 * done then the default behavior is to escalate the failure, which amounts to
 * failing this actor with the same exception that the child actor failed with.
 */
final case class Failed(cause: Throwable, child: ActorRef[Nothing]) extends Signal
/**
 * The actor can register for a notification in case no message is received
 * within a given time window, and the signal that is raised in this case is
 * this one. See also [[ActorContext#setReceiveTimeout]].
 */
final case object ReceiveTimeout extends Signal
/**
 * Lifecycle signal that is fired when an Actor that was watched has terminated.
 * Watching is performed by invoking the
 * [[akka.typed.ActorContext!.watch[U]* watch]] method. The DeathWatch service is
 * idempotent, meaning that registering twice has the same effect as registering
 * once. Registration does not need to happen before the Actor terminates, a
 * notification is guaranteed to arrive after both registration and termination
 * have occurred. Termination of a remote Actor can also be effected by declaring
 * the Actor’s home system as failed (e.g. as a result of being unreachable).
 */
final case class Terminated(ref: ActorRef[Nothing]) extends Signal

/**
 * The parent of an actor decides upon the fate of a failed child actor by
 * encapsulating its next behavior in one of the four wrappers defined within
 * this class.
 *
 * Failure responses have an associated precedence that ranks them, which is in
 * descending importance:
 *
 *  - Escalate
 *  - Stop
 *  - Restart
 *  - Resume
 */
object Failed {

  /**
   * Failure responses are in some cases communicated by using the companion
   * objects of the wrapper behaviors, see the [[StepWise]] behavior for an
   * example.
   */
  sealed trait Decision

  case object NoFailureResponse extends Decision

  /**
   * Resuming the child actor means that the result of processing the message
   * on which it failed is just ignored, the previous state will be used to
   * process the next message. The message that triggered the failure will not
   * be processed again.
   */
  case object Resume extends Decision

  /**
   * Restarting the child actor means resetting its behavior to the initial
   * one that was provided during its creation (i.e. the one which was passed
   * into the [[Props]] constructor). The previously failed behavior will
   * receive a [[PreRestart]] signal before this happens and the replacement
   * behavior will receive a [[PostRestart]] signal afterwards.
   */
  case object Restart extends Decision

  /**
   * Stopping the child actor will free its resources and eventually
   * (asynchronously) unregister its name from the parent. Completion of this
   * process can be observed by watching the child actor and reacting to its
   * [[Terminated]] signal.
   */
  case object Stop extends Decision

  /**
   * The default response to a failure in a child actor is to escalate the
   * failure, entailing that the parent actor fails as well. This is equivalent
   * to an exception unwinding the call stack, but it applies to the supervision
   * hierarchy instead.
   */
  case object Escalate extends Decision

}

/**
 * Several commonly used ways to express behaviors are bundled within this
 * object.
 */
object Behavior {

  /**
   * This type of behavior allows to handle all incoming messages within
   * the same user-provided partial function, be that a user message or a system
   * signal. For messages that do not match the partial function the same
   * behavior is emitted without change. This does entail that unhandled
   * failures of child actors will lead to a failure in this actor.
   *
   * For the lifecycle notifications pertaining to the actor itself this
   * behavior includes a fallback mechanism: an unhandled [[PreRestart]] signal
   * will terminate all child actors (transitively) and then emit a [[PostStop]]
   * signal in addition, whereas an unhandled [[PostRestart]] signal will emit
   * an additional [[PreStart]] signal.
   */
  // TODO introduce Message/Signal ADT instead of using Either
  case class Full[T](behavior: PartialFunction[(ActorContext[T], Either[Signal, T]), Behavior[T]]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      lazy val fallback: ((ActorContext[T], Either[Signal, T])) ⇒ Behavior[T] = _ ⇒
        msg match {
          case PreRestart(_) ⇒
            ctx.children foreach { child ⇒
              ctx.unwatch(child.ref)
              ctx.stop(child.path.name)
            }
            behavior.applyOrElse((ctx, Left(PostStop)), fallback)
          case PostRestart(_) ⇒ behavior.applyOrElse((ctx, Left(PreStart)), fallback)
          case _              ⇒ Unhandled
        }
      behavior.applyOrElse((ctx, Left(msg)), fallback)
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      behavior.applyOrElse((ctx, Right(msg)), (_: (ActorContext[T], Either[Signal, T])) ⇒ Unhandled)
    }
    override def toString = s"Full(${LN.forClass(behavior.getClass)})"
  }

  /**
   * This type of behavior expects a total function that describes the actor’s
   * reaction to all system signals or user messages, without providing a
   * fallback mechanism for either. If you use partial function literal syntax
   * to create the supplied function then any message not matching the list of
   * cases will fail this actor with a [[scala.MatchError]].
   */
  case class FullTotal[T](behavior: (ActorContext[T], Either[Signal, T]) ⇒ Behavior[T]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal) = behavior(ctx, Left(msg))
    override def message(ctx: ActorContext[T], msg: T) = behavior(ctx, Right(msg))
    override def toString = s"FullTotal(${LN.forClass(behavior.getClass)})"
  }

  /**
   * This type of behavior is created from a total function from the declared
   * message type to the next behavior, which means that all possible incoming
   * messages for the given type must be handled. All system signals are
   * ignored by this behavior, which implies that a failure of a child actor
   * will be escalated unconditionally.
   *
   * This behavior type is most useful for leaf actors that do not create child
   * actors themselves.
   */
  case class Simple[T](behavior: T ⇒ Behavior[T]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = msg match {
      case _ ⇒ Unhandled
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = behavior(msg)
    override def toString = s"Simple(${LN.forClass(behavior.getClass)})"
  }

  /**
   * This type of behavior is a variant of [[Behavior.Simple]] that does not
   * allow the actor to change behavior. It is an efficient choice for stateless
   * actors, possibly entering such a behavior after finishing its
   * initialization (which may be modeled using any of the other behavior types).
   *
   * This behavior type is most useful for leaf actors that do not create child
   * actors themselves.
   */
  case class Static[T](behavior: T ⇒ Unit) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = msg match {
      case _ ⇒ Unhandled
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      behavior(msg)
      this
    }
    override def toString = s"Static(${LN.forClass(behavior.getClass)})"
  }

  /**
   * A variant of [[Behavior.Simple]] that allows access to the [[ActorContext]]
   * for scheduling a [[ReceiveTimeout]] or watching other actors. All system signals are
   * ignored by this behavior, which implies that a failure of a child actor
   * will be escalated unconditionally.
   *
   * This behavior type is most useful for leaf actors that do not create child
   * actors themselves.
   */
  case class Contextual[T](behavior: (ActorContext[T], T) ⇒ Behavior[T]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = msg match {
      case _ ⇒ Unhandled
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = behavior(ctx, msg)
    override def toString = s"Contextual(${LN.forClass(behavior.getClass)})"
  }

  /**
   * A variant of [[Behavior.Contextual]] that also allows handling of system
   * notifications. The difference to [[Behavior.Full]] is that it separates
   * the channels for signals and user messages into different fucntions,
   * enabling the latter to benefit from exhaustiveness checking since it employs
   * a total function.
   *
   * This behavior employs the same fallback mechanism for [[PreRestart]] and
   * [[PostRestart]] signals as [[Behavior.Full]].
   */
  case class Composite[T](mgmt: PartialFunction[(ActorContext[T], Signal), Behavior[T]], behavior: (ActorContext[T], T) ⇒ Behavior[T]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      lazy val fallback: ((ActorContext[T], Signal)) ⇒ Behavior[T] = _ ⇒
        msg match {
          case PreRestart(_) ⇒
            ctx.children foreach { child ⇒
              ctx.unwatch(child.ref)
              ctx.stop(child.path.name)
            }
            mgmt.applyOrElse((ctx, PostStop), fallback)
          case PostRestart(_) ⇒ mgmt.applyOrElse((ctx, PreStart), fallback)
          case _              ⇒ Unhandled
        }
      mgmt.applyOrElse((ctx, msg), fallback)
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = behavior(ctx, msg)
    override def toString = s"Composite(${LN.forClass(mgmt.getClass)},${LN.forClass(behavior.getClass)})"
  }

  case class SynchronousSelf[T](f: ActorRef[T] ⇒ Behavior[T]) extends Behavior[T] {
    private val inbox = Inbox.sync[T]("syncbox")
    private var _behavior = f(inbox.ref)
    private def behavior = _behavior
    private def setBehavior(ctx: ActorContext[T], b: Behavior[T]): Unit =
      _behavior = Behavior.canonicalize(ctx, b, _behavior)

    @tailrec private def run(ctx: ActorContext[T], next: Behavior[T]): Behavior[T] =
      if (inbox.hasMessages) run(ctx, next.message(ctx, inbox.receiveMsg()))
      else next

    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      setBehavior(ctx, run(ctx, behavior.management(ctx, msg)))
      this
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      setBehavior(ctx, run(ctx, behavior.message(ctx, msg)))
      this
    }

    override def toString: String = s"SynchronousSelf($behavior)"
  }

  /**
   * A behavior combinator that feeds incoming messages and signals both into
   * the left and right sub-behavior and allows them to evolve independently of
   * each other. When one of the sub-behaviors terminates the other takes over
   * exclusively. When both sub-behaviors respond to a [[Failed]] signal, the
   * response with the higher precedence is chosen (see [[Failed$]]).
   */
  case class And[T](left: Behavior[T], right: Behavior[T]) extends Behavior[T] {

    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      val nextLeft = canonicalize(ctx, left.management(ctx, msg), left)
      val nextRight = canonicalize(ctx, right.management(ctx, msg), right)
      val leftAlive = isAlive(nextLeft)
      val rightAlive = isAlive(nextRight)

      if (leftAlive && rightAlive) And(nextLeft, nextRight)
      else if (leftAlive) nextLeft
      else if (rightAlive) nextRight
      else Stopped
    }

    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      val nextLeft = canonicalize(ctx, left.message(ctx, msg), left)
      val nextRight = canonicalize(ctx, right.message(ctx, msg), right)
      val leftAlive = isAlive(nextLeft)
      val rightAlive = isAlive(nextRight)

      if (leftAlive && rightAlive) And(nextLeft, nextRight)
      else if (leftAlive) nextLeft
      else if (rightAlive) nextRight
      else Stopped
    }
  }

  /**
   * A behavior combinator that feeds incoming messages and signals either into
   * the left or right sub-behavior and allows them to evolve independently of
   * each other. The message or signal is passed first into the left sub-behavior
   * and only if that results in [[Behavior$.Unhandled]] is it passed to the right
   * sub-behavior. When one of the sub-behaviors terminates the other takes over
   * exclusively. When both sub-behaviors respond to a [[Failed]] signal, the
   * response with the higher precedence is chosen (see [[Failed$]]).
   */
  case class Or[T](left: Behavior[T], right: Behavior[T]) extends Behavior[T] {

    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] =
      left.management(ctx, msg) match {
        case b if isUnhandled(b) ⇒
          val nr = canonicalize(ctx, right.management(ctx, msg), right)
          if (isAlive(nr)) Or(left, nr) else left
        case nl ⇒
          val next = canonicalize(ctx, nl, left)
          if (isAlive(next)) Or(next, right) else right
      }

    override def message(ctx: ActorContext[T], msg: T): Behavior[T] =
      left.message(ctx, msg) match {
        case b if isUnhandled(b) ⇒
          val nr = canonicalize(ctx, right.message(ctx, msg), right)
          if (isAlive(nr)) Or(left, nr) else left
        case nl ⇒
          val next = canonicalize(ctx, nl, left)
          if (isAlive(next)) Or(next, right) else right
      }
  }

  // TODO
  // case class Selective[T](timeout: FiniteDuration, selector: PartialFunction[T, Behavior[T]], onTimeout: () ⇒ Behavior[T])

  /**
   * A behavior decorator that extracts the self [[ActorRef]] while receiving the
   * the first signal or message and uses that to construct the real behavior
   * (which will then also receive that signal or message).
   *
   * Example:
   * {{{
   * SelfAware[MyCommand] { self =>
   *   Simple {
   *     case cmd =>
   *   }
   * }
   * }}}
   *
   * This can also be used together with implicitly sender-capturing message
   * types:
   * {{{
   * case class OtherMsg(msg: String)(implicit val replyTo: ActorRef[Reply])
   *
   * SelfAware[MyCommand] { implicit self =>
   *   Simple {
   *     case cmd =>
   *       other ! OtherMsg("hello") // assuming Reply <: MyCommand
   *   }
   * }
   * }}}
   */
  def SelfAware[T](behavior: ActorRef[T] ⇒ Behavior[T]): Behavior[T] =
    FullTotal { (ctx, msg) ⇒
      msg match {
        case Left(signal) ⇒
          val behv = behavior(ctx.self)
          canonicalize(ctx, behv.management(ctx, signal), behv)
        case Right(msg) ⇒
          val behv = behavior(ctx.self)
          canonicalize(ctx, behv.message(ctx, msg), behv)
      }
    }

  /**
   * A behavior decorator that extracts the [[ActorContext]] while receiving the
   * the first signal or message and uses that to construct the real behavior
   * (which will then also receive that signal or message).
   *
   * Example:
   * {{{
   * ContextAware[MyCommand] { ctx => Simple {
   *     case cmd =>
   *       ...
   *   }
   * }
   * }}}
   */
  def ContextAware[T](behavior: ActorContext[T] ⇒ Behavior[T]): Behavior[T] =
    FullTotal { (ctx, msg) ⇒
      msg match {
        case Left(signal) ⇒
          val behv = behavior(ctx)
          canonicalize(ctx, behv.management(ctx, signal), behv)
        case Right(msg) ⇒
          val behv = behavior(ctx)
          canonicalize(ctx, behv.message(ctx, msg), behv)
      }
    }

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior. This is provided in order to
   * avoid the allocation overhead of recreating the current behavior where
   * that is not necessary.
   */
  def Same[T]: Behavior[T] = sameBehavior.asInstanceOf[Behavior[T]]

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior, including the hint that the
   * message has not been handled. This hint may be used by composite
   * behaviors that delegate (partial) handling to other behaviors.
   */
  def Unhandled[T]: Behavior[T] = unhandledBehavior.asInstanceOf[Behavior[T]]

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure. The PostStop
   * signal that results from stopping this actor will NOT be passed to the
   * current behavior, it will be effectively ignored. In order to install a
   * cleanup action please refer to
   * [[akka.typed.Behavior$.Stopped[T](cleanup* Stopped(cleanup: () => Unit)]].
   */
  def Stopped[T]: Behavior[T] = stoppedBehavior.asInstanceOf[Behavior[T]]

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure.
   *
   * TODO: think about whether the ability to defer the cleanup is really necessary
   *
   * @param cleanup an action to run in response to the PostStop signal that
   *                will result from stopping this actor
   */
  def Stopped[T](cleanup: () ⇒ Unit): Behavior[T] = new stoppedBehavior(cleanup)

  /**
   * This behavior does not handle any inputs, it is completely inert.
   */
  def Empty[T]: Behavior[T] = emptyBehavior.asInstanceOf[Behavior[T]]

  /**
   * INTERNAL API.
   */
  private[akka] object emptyBehavior extends Behavior[Any] {
    override def management(ctx: ActorContext[Any], msg: Signal): Behavior[Any] = Unhandled
    override def message(ctx: ActorContext[Any], msg: Any): Behavior[Any] = Unhandled
    override def toString = "Empty"
  }

  /**
   * This behavior does not handle any inputs, it is completely inert.
   */
  def Ignore[T]: Behavior[T] = ignoreBehavior.asInstanceOf[Behavior[T]]

  /**
   * INTERNAL API.
   */
  private[akka] object ignoreBehavior extends Behavior[Any] {
    override def management(ctx: ActorContext[Any], msg: Signal): Behavior[Any] = Same
    override def message(ctx: ActorContext[Any], msg: Any): Behavior[Any] = Same
    override def toString = "Ignore"
  }

  /**
   * INTERNAL API.
   */
  private[akka] object unhandledBehavior extends Behavior[Nothing] {
    override def management(ctx: ActorContext[Nothing], msg: Signal): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def message(ctx: ActorContext[Nothing], msg: Nothing): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def toString = "Unhandled"
  }

  /**
   * INTERNAL API.
   */
  private[akka] object sameBehavior extends Behavior[Nothing] {
    override def management(ctx: ActorContext[Nothing], msg: Signal): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def message(ctx: ActorContext[Nothing], msg: Nothing): Behavior[Nothing] = throw new UnsupportedOperationException("Not Implemented")
    override def toString = "Same"
  }

  /**
   * INTERNAL API.
   */
  private[akka] class stoppedBehavior[T](cleanup: () ⇒ Unit) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      assert(msg == PostStop, s"stoppedBehavior received $msg (only PostStop is expected)")
      cleanup()
      this
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = throw new UnsupportedOperationException("Not Implemented")
    override def toString = "Stopped"
  }

  /**
   * INTERNAL API.
   */
  private[akka] object stoppedBehavior extends stoppedBehavior(() ⇒ ())

  /**
   * Given a possibly wrapped behavior (see [[Behavior.Wrapper]]) and a
   * “current” behavior (which defines the meaning of encountering a [[#Same]]
   * behavior) this method unwraps the behavior such that the innermost behavior
   * is returned, i.e. it removes the decorations.
   */
  def canonicalize[T](ctx: ActorContext[T], behavior: Behavior[T], current: Behavior[T]): Behavior[T] =
    behavior match {
      case s: stoppedBehavior[t] ⇒ { s.management(ctx, PostStop); Stopped }
      case `sameBehavior`        ⇒ current
      case `unhandledBehavior`   ⇒ current
      case other                 ⇒ other
    }

  def isAlive[T](behavior: Behavior[T]): Boolean = !behavior.isInstanceOf[stoppedBehavior[_]]

  def isUnhandled[T](behavior: Behavior[T]): Boolean = behavior eq unhandledBehavior

}

