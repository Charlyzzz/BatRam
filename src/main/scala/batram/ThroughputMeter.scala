package batram

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import batram.Stats.UpdateStats

import scala.concurrent.duration.FiniteDuration

object ThroughputMeter {

  def apply(period: FiniteDuration, statsActor: ActorRef[UpdateStats]): Behavior[Command] = Behaviors.withTimers[Protocol] { timer =>
    Behaviors.setup { ctx =>
      ctx.setLoggerName(ThroughputMeter.getClass)

      def counting(n: Int): Behavior[Protocol] = Behaviors.receiveMessage {
        case EmitResult =>
          val rps = n / period.length
          ctx.log.info(s"$rps req/s")
          //ctx.log.info(ctx.system.printTree)
          statsActor ! UpdateStats(rps.toInt)
          counting(0)
        case ComputeSuccess => counting(n + 1)
        case ComputeError =>
          ctx.log.warn("Received error")
          Behaviors.same
      }

      timer.startTimerAtFixedRate(EmitResult, period)
      counting(0)
    }
  }.narrow

  trait Protocol

  trait Command extends Protocol

  object ComputeSuccess extends Command

  object ComputeError extends Command

  private object EmitResult extends Protocol

}
