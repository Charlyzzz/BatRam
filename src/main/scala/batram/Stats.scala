package batram

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}

case class Stats(throughputPerSecond: Int)

object Stats {

  def behavior(eventSink: Sink[ServerSentEvent, _])(implicit materializer: Materializer): Behavior[UpdateStats] =
    Behaviors.setup { _ =>
      val (queue, source) = Source.queue[ServerSentEvent](1024, OverflowStrategy.dropNew).preMaterialize()
      source.runWith(eventSink)

      def track(currentStats: Stats): Behavior[UpdateStats] = Behaviors.receiveMessage {
        case UpdateStats(throughputPerSecond) =>
          val newStats = currentStats.copy(throughputPerSecond)
          queue.offer(newStats.toSSE)
          track(newStats)
      }

      track(Stats(0))
    }

  case class UpdateStats(throughputPerSecond: Int)

  implicit class StatsExtensions(stats: Stats) {
    def toSSE: ServerSentEvent = ServerSentEvent(s"""{"rps":${stats.throughputPerSecond}}""")
  }

}