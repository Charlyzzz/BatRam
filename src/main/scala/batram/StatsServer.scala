package batram

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.server.Directives.{complete, concat, get, getFromResource, path, pathEndOrSingleSlash}
import akka.stream.scaladsl.{BroadcastHub, Sink}

object StatsServer {

  def apply(port: Int)(implicit actorSystem: ActorSystem): Sink[ServerSentEvent, NotUsed] = {
    val (source, sink) = BroadcastHub.sink[ServerSentEvent](1024).preMaterialize()

    val route = concat(
      path("events") {
        get {
          complete {
            source
          }
        }
      },
      pathEndOrSingleSlash {
        getFromResource("index.html")
      }
    )
    Http().bindAndHandle(route, "0.0.0.0", port).foreach {
      serverBinding => actorSystem.log.info(s"monitor started at http://localhost:${serverBinding.localAddress.getPort}")
    }(actorSystem.dispatcher)
    sink
  }


}
