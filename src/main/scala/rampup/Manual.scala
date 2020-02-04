package rampup

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import batram.DynamicThrottle.{Message, Update}
import rampup.Linear.Target

import scala.concurrent.duration._

object Manual {

  def apply()(throttler: ActorRef[Message]): Behavior[Target] = Behaviors.receiveMessage[Target] {
    case Target(rps) =>
      throttler ! Update(rps, 1.second)
      Behaviors.same
  }
}
