import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.scaladsl.Flow
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext

object TestServer extends App {

  implicit val system: ActorSystem = ActorSystem("test-server", ConfigFactory.empty)
  implicit val executionContext: ExecutionContext = system.dispatcher

  private val handler = Flow[HttpRequest].map { r =>
    r.discardEntityBytes()
    HttpResponse(StatusCodes.OK, entity = "OK")
  }

  val serverSettings = ServerSettings(system)

  Http().bindAndHandle(handler = handler, interface = "0.0.0.0", port = 8082, settings = serverSettings)
    .flatMap(_.whenTerminated)
    .onComplete(_ => system.terminate())
}
