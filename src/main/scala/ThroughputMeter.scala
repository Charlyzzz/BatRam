import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._

object ThroughputMeter {

  trait Message

  object ComputeSuccess extends Message

  object ComputeError extends Message

  object EmitResult extends Message

  def apply(period: FiniteDuration): Behavior[Message] = Behaviors.withTimers { timer =>
    Behaviors.setup { ctx =>
      ctx.setLoggerName(ThroughputMeter.getClass)

      def counting(n: Int): Behavior[Message] = Behaviors.receiveMessagePartial {
        case EmitResult =>
          ctx.log.info(s"${n / period.length} req/s")
          counting(0)
        case ComputeSuccess => counting(n + 1)
      }

      timer.startTimerWithFixedDelay(EmitResult, period)
      counting(0)
    }
  }
}


