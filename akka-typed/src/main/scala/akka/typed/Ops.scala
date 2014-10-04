/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.typed

import language.implicitConversions

object Ops {

  implicit class ActorSystemOps(val sys: akka.actor.ActorSystem) extends AnyVal {
    def spawn[T](props: Props[T]): ActorRef[T] =
      ActorRef(sys.actorOf(Props.untyped(props)))
    def spawn[T](props: Props[T], name: String): ActorRef[T] =
      ActorRef(sys.actorOf(Props.untyped(props), name))
  }

  implicit class ActorContextOps(val ctx: akka.actor.ActorContext) extends AnyVal {
    def spawn[T](props: Props[T]): ActorRef[T] =
      ActorRef(ctx.actorOf(Props.untyped(props)))
    def spawn[T](props: Props[T], name: String): ActorRef[T] =
      ActorRef(ctx.actorOf(Props.untyped(props), name))
  }

  implicit def actorRefAdapter(ref: akka.actor.ActorRef): ActorRef[Any] = ActorRef(ref)

}