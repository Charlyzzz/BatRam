package rampup

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import batram.DynamicThrottle.{Message, Update}

import scala.concurrent.duration._


case class Stable(value: Int)

case class MovingTowards(current: Int, target: Int, delta: Int) {

  val reached: Boolean = current == target

  def step: MovingTowards = {
    if (target > current)
      copy(target.min(current + delta))
    else if (target < current)
      copy(target.max(current - delta))
    else
      this
  }
}

object Linear {

  trait Protocol

  case class TryReach(rps: Int) extends Protocol

  case class Target(rps: Int) extends Protocol

  def apply(steps: Int, totalDuration: FiniteDuration)(throttler: ActorRef[Message]): Behavior[Target] = Behaviors.setup[Protocol] { ctx =>

    val stepDuration = totalDuration.div(steps)

    def working(current: MovingTowards): Behavior[Protocol] = Behaviors.receiveMessage {
      case _: Target =>
        ctx.log.warn("ignoring received target until previous is reached")
        Behaviors.same
      case TryReach(rps) =>
        if (current.reached)
          idle(Stable(rps))
        else {
          val newState = current.step
          throttler ! Update(newState.current, 1.second)
          ctx.scheduleOnce(stepDuration, ctx.self, TryReach(rps))
          working(newState)
        }
    }

    def idle(current: Stable): Behavior[Protocol] = Behaviors.receiveMessagePartial {
      case Target(rps) =>
        ctx.self ! TryReach(rps)
        working(MovingTowards(current.value, rps, rps / steps))
    }

    idle(Stable(0))
  }.narrow
}
