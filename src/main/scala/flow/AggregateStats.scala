package flow

import akka.http.scaladsl.model.HttpResponse
import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}

import scala.concurrent.duration._
import scala.util.{Success, Try}

case class Stats(var ok: Int, var ko: Int, var latency: Long, var requestCount: Int) {
  def aggregateSuccessful(msDuration: Long): Unit = {
    val latencyCumulativeAverage = (msDuration + (requestCount * latency)) / (requestCount + 1)
    ok += 1
    latency = latencyCumulativeAverage
    requestCount += 1
  }

  def aggregateUnsuccessful(): Unit = {
    ko += 1
    requestCount += 1
  }

  def reset(): Unit = {
    ok = 0
    ko = 0
    latency = 0
    requestCount = 0
  }
}

class AggregateStats(aggregationPeriod: FiniteDuration)
  extends GraphStage[FlowShape[(Try[HttpResponse], Long), Stats]] {

  val in = Inlet[(Try[HttpResponse], Long)]("AggregateStats.in")
  val out = Outlet[Stats]("AggregateStats.out")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogicWithLogging(shape) with InHandler with OutHandler {

      setHandlers(in, out, this)

      val stats = Stats(0, 0, 0, 0)

      override def preStart(): Unit = {
        scheduleAtFixedRate(Symbol("emit-stats"), 0.seconds, aggregationPeriod)
      }

      override def onPush(): Unit = {
        grab(in) match {
          case (Success(_), duration) =>
            stats.aggregateSuccessful(duration)
          case _ =>
            stats.aggregateUnsuccessful()
        }
        onPull()
      }

      override def onPull(): Unit =
        if (!hasBeenPulled(in))
          pull(in)

      override protected def onTimer(timerKey: Any): Unit = {
        emit(out, stats.copy())
        stats.reset()
        onPull()
      }
    }

  override def shape = FlowShape.of(in, out)
}


