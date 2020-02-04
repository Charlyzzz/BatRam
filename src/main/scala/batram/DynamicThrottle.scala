package batram

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import batram.DynamicThrottle.{LinkThrottle, Message, Update}

import scala.concurrent.duration._

class DynamicThrottle[A](n: Int, per: FiniteDuration)(throttler: ActorRef[Message]) extends GraphStage[FlowShape[A, A]] {
  require(n >= 0, "number of elements should be > 0")
  require(per.length > 0, "time length should be > 0")

  val in = Inlet[A]("DynamicThrottle.in")
  val out = Outlet[A]("DynamicThrottle.out")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogicWithLogging(shape) with InHandler with OutHandler {

      var cap = n
      var remainingElements = n
      var onHold = false
      var update: Option[Update] = None

      override def preStart(): Unit = {
        scheduleAtFixedRate(NotUsed, 0.seconds, per)
        val self = getStageActor {
          case (_, m: Update) =>
            update = Some(m)
            log.info(s"$m")
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
        update.foreach { u =>
          cap = u.n
          cancelTimer(NotUsed)
          scheduleAtFixedRate(NotUsed, 0.seconds, u.per)
          update = None
        }
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

  val behavior: Behavior[Message] = Behaviors.setup { _ =>
    def linked(actor: ActorRef[Any]): Behaviors.Receive[Message] = Behaviors.receiveMessagePartial {
      case u: Update =>
        actor ! u
        Behaviors.same
    }

    def waitingLink: Behaviors.Receive[Message] = Behaviors.receiveMessagePartial {
      case LinkThrottle(actor) => linked(actor)
    }

    waitingLink
  }

  trait Message

  case class LinkThrottle(stageActor: ActorRef[Any]) extends Message

  case class Update(n: Int, per: FiniteDuration) extends Message

}
