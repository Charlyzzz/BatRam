import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.DelayOverflowStrategy
import akka.stream.scaladsl.Flow
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object TestServer extends App {

  implicit val system: ActorSystem = ActorSystem("test-server", ConfigFactory.empty)
  implicit val executionContext: ExecutionContext = system.dispatcher

  private val handler = Flow[HttpRequest].delay(400.millis, DelayOverflowStrategy.fail).map(_ => HttpResponse())

  val serverSettings = ServerSettings(system)
    .withMaxConnections(16384)
    .withPipeliningLimit(1)

  Http().bindAndHandle(handler = handler, interface = "0.0.0.0", port = 3000, settings = serverSettings)
    .flatMap(_.whenTerminated)
    .onComplete(_ => system.terminate())
}
