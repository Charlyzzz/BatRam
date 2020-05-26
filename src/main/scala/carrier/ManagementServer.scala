package carrier

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives.{as, complete, concat, entity, get, getFromResource, path, pathEndOrSingleSlash, post}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{BroadcastHub, Flow, Sink, Source}
import carrier.Radar.Position

import scala.concurrent.Future

object ManagementServer {

  def apply(port: Int)(implicit actorSystem: ActorSystem):
  (Sink[Position, NotUsed], Source[Int, NotUsed], Future[Http.ServerBinding]) = {

    val (sseSource, sseSink) = BroadcastHub.sink[ServerSentEvent](1024).preMaterialize()
    val positions = Flow[Position].map(toSSE).to(sseSink)

    val (fireRateQueue, fireRate) = Source.queue[Int](100, OverflowStrategy.dropNew).preMaterialize()

    val route = concat(
      pathEndOrSingleSlash {
        getFromResource("index.html")
      },
      path("rate") {
        post {
          entity(as[String]) { rate =>
            complete {
              fireRateQueue.offer(rate.toInt)
              "Ok"
            }
          }
        }
      },
      path("metrics") {
        get {
          complete {
            sseSource
          }
        }
      }
    )
    val futureBinding = Http().bindAndHandle(route, "0.0.0.0", port)
    futureBinding.foreach {
      serverBinding => actorSystem.log.info(s"monitor started at http://localhost:${serverBinding.localAddress.getPort}")
    }(actorSystem.dispatcher)
    (positions, fireRate, futureBinding)
  }

  def toSSE(position: Position) =
    ServerSentEvent(s"""{"rps": ${position.hits + position.misses}, "address": "${position.id}"}""")

}
