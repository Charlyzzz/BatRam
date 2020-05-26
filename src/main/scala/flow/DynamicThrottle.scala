package flow

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import flow.DynamicThrottle.{DynamicThrottleMessage, LinkThrottle, Update}

import scala.concurrent.duration._

class DynamicThrottle[A](n: Int)(throttler: ActorRef[DynamicThrottleMessage]) extends GraphStage[FlowShape[A, A]] {
  require(n >= 0, "number of elements should be > 0")

  val in = Inlet[A]("DynamicThrottle.in")
  val out = Outlet[A]("DynamicThrottle.out")
  val throttlerPeriod: FiniteDuration = 1.second

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogicWithLogging(shape) with InHandler with OutHandler {

      var cap = n
      var remainingElements = n
      var onHold = false

      def setNewCap(newCap: Int): Unit = {
        if (newCap >= cap) {
          val capDiff = newCap - cap
          cap = newCap
          remainingElements += capDiff
        } else {
          val capDiff = newCap - cap
          cap = newCap
          remainingElements = (remainingElements - capDiff).max(0)
        }
      }

      override def preStart(): Unit = {
        scheduleAtFixedRate(NotUsed, 0.seconds, throttlerPeriod)
        val self = getStageActor {
          case (_, Update(n)) => setNewCap(n)
          case _ =>
        }
        throttler ! LinkThrottle(self.ref.toTyped)
      }

      override def onPush(): Unit = {
        if (remainingElements != 0) {
          emit(out, grab(in))
          remainingElements -= 1
        } else {
          onHold = true
        }
      }

      override def onPull(): Unit = pull(in)

      override protected def onTimer(timerKey: Any): Unit = {
        remainingElements = cap
        if (onHold) {
          onHold = false
          onPush()
        }
      }

      setHandlers(in, out, this)
    }

  override def shape = FlowShape.of(in, out)
}

object DynamicThrottle {

  val behavior: Behavior[DynamicThrottleMessage] = Behaviors.setup { _ =>
    def linked(actor: ActorRef[Any]) = Behaviors.receiveMessagePartial[DynamicThrottleMessage] {
      case u: Update =>
        actor ! u
        Behaviors.same
    }

    val waitingLink = Behaviors.receiveMessagePartial[DynamicThrottleMessage] {
      case LinkThrottle(actor) => linked(actor)
    }

    waitingLink
  }

  trait DynamicThrottleMessage

  case class LinkThrottle(stageActor: ActorRef[Any]) extends DynamicThrottleMessage

  case class Update(n: Int) extends DynamicThrottleMessage

}
