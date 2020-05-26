package rampup

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._


object LinearRamping {

  //  case class Stable(value: Int)
  //
  //  case class MovingTowards(current: Int, target: Int, delta: Int) {
  //
  //    val reached: Boolean = current == target
  //
  //    def step: MovingTowards = {
  //      if (target > current)
  //        copy(target.min(current + delta))
  //      else if (target < current)
  //        copy(target.max(current - delta))
  //      else
  //        this
  //    }
  //  }

  case object ModifyCurrent


  def apply(step: Int, stepDuration: FiniteDuration, adapter: Int => Unit): Behavior[Int] = Behaviors.setup[Any] { ctx =>

    def scheduleIncrease(): Unit = ctx.scheduleOnce(stepDuration, ctx.self, ModifyCurrent)

    def working(current: Int, target: Int): Behavior[Any] = Behaviors.receiveMessage {
      case newNextTarget: Int =>
        ctx.log.debug(s"Received new target $newNextTarget while working")
        working(current, newNextTarget)
      case ModifyCurrent =>
        val newCurrent = (current + step).min(target)
        ctx.log.debug(s"Updating rate $newCurrent")
        adapter(newCurrent)
        if (newCurrent == target) {
          ctx.log.debug("Finished, transitioning to idle")
          idle(newCurrent)
        } else {
          scheduleIncrease()
          working(newCurrent, target)
        }
    }

    def idle(current: Int): Behavior[Any] = Behaviors.receiveMessagePartial {
      case newTarget: Int =>
        ctx.log.debug(s"Received new target $newTarget while idle")
        scheduleIncrease()
        working(current, newTarget)
    }

    idle(0)
  }.narrow
}