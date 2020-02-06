package batram

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Sink, Source}

import scala.concurrent.Future

object ManagementServer {

  def apply(port: Int)(implicit actorSystem: ActorSystem):
  (Sink[Stats, NotUsed], Source[Int, NotUsed], Future[Http.ServerBinding]) = {

    val (statsSink, rpsSource) =
      MergeHub.source[Stats](10)
        .map(_.toSSE)
        .toMat(BroadcastHub.sink[ServerSentEvent](1024))(Keep.both).run()

    val (queue, rateSource) = Source.queue[Int](1, OverflowStrategy.dropNew).preMaterialize()

    val route = concat(
      path("rate") {
        post {
          entity(as[String]) { rate =>
            complete {
              queue.offer(rate.toInt)
              "Ok"
            }
          }
        }
      },
      path("metrics") {
        get {
          complete {
            rpsSource
          }
        }
      },
      pathEndOrSingleSlash {
        getFromResource("index.html")
      }
    )
    val futureBinding = Http().bindAndHandle(route, "0.0.0.0", port)
    futureBinding.foreach {
      serverBinding => actorSystem.log.info(s"monitor started at http://localhost:${serverBinding.localAddress.getPort}")
    }(actorSystem.dispatcher)
    (statsSink, rateSource, futureBinding)
  }

  implicit class StatsExtensions(stats: Stats) {
    def toSSE: ServerSentEvent = ServerSentEvent(s"""{"rps":${stats.throughputPerSecond}, "address": "${stats.address}"}""")
  }

}
