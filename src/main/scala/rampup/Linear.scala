package rampup

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import rampup.Handler.serviceKey

import scala.concurrent.duration._


object Linear {

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

  case class TryReach(rps: Int)

  def apply(steps: Int, totalDuration: FiniteDuration)(throttler: ActorRef[Int]): Behavior[Int] = Behaviors.setup[Any] { ctx =>
    ctx.system.receptionist ! Receptionist.register(serviceKey, ctx.self)

    val stepDuration = totalDuration.div(steps)

    def working(current: MovingTowards): Behavior[Any] = Behaviors.receiveMessage {
      case _: Int =>
        ctx.log.warn("ignoring new target until previous is reached")
        Behaviors.same
      case TryReach(rps) =>
        if (current.reached)
          idle(Stable(rps))
        else {
          val newState = current.step
          throttler ! newState.current
          ctx.scheduleOnce(stepDuration, ctx.self, TryReach(rps))
          working(newState)
        }
    }

    def idle(current: Stable): Behavior[Any] = Behaviors.receiveMessagePartial {
      case rps: Int =>
        ctx.self ! TryReach(rps)
        val delta = rps / steps
        working(MovingTowards(current.value, rps, delta))
    }

    idle(Stable(0))
  }.narrow
}
