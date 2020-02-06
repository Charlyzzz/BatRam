package batram

import akka.actor.Address
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.Cluster
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}

case class Stats(throughputPerSecond: Int, address: Address)

object Stats {

  def behavior(eventSink: Sink[Stats, _])(implicit materializer: Materializer): Behavior[UpdateStats] =
    Behaviors.setup { ctx =>
      val address = Cluster(ctx.system).selfMember.address
      val (queue, source) = Source.queue[Stats](1024, OverflowStrategy.dropNew).preMaterialize()
      source.runWith(eventSink)

      def track(currentStats: Stats): Behavior[UpdateStats] = Behaviors.receiveMessage {
        case UpdateStats(throughputPerSecond) =>
          val newStats = currentStats.copy(throughputPerSecond)
          queue.offer(newStats)
          track(newStats)
      }

      track(Stats(0, address))
    }

  case class UpdateStats(throughputPerSecond: Int)

  implicit class StatsExtensions(stats: Stats) {
    def toSSE: ServerSentEvent = ServerSentEvent(s"""{"rps":${stats.throughputPerSecond}}""")
  }

}