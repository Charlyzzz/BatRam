import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Timers}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object PoolTest extends App {
  val uri = Uri("http://10.0.1.5:8082")
  implicit val system: ActorSystem = ActorSystem("pool", ConfigFactory.parseString(
    """
      |
      | akka.io.tcp {
      |  nr-of-selectors = 3
      | }
      |""".stripMargin))

  val address = uri.authority.host.address()
  val port = uri.authority.port

  object Track

  object Reset

  class Tracker extends Actor with ActorLogging with Timers {
    var requestCount = 0

    override def preStart(): Unit = {
      timers.startTimerAtFixedRate("timerName", Reset, 1.second)
    }

    override def receive: Receive = {
      case Track =>
        requestCount += 1
      case Reset =>
        log.info(requestCount.toString)
        requestCount = 0
    }
  }

  val tracker = system.actorOf(Props[Tracker])

  pool


  Source
    .repeat(HttpRequest(uri = uri))
    //.throttle(10000, 1.second)
    .via(pool)
    .runForeach { response =>
      tracker ! Track
      response.discardEntityBytes()
    }

  def pool: Flow[HttpRequest, HttpResponse, NotUsed] = {
    val poolSettings = ConnectionPoolSettings(system)
      .withMaxConnections(512)
    val poolFlow = Http().cachedHostConnectionPool[NotUsed](address, port, poolSettings)
    Flow[HttpRequest]
      .map((_, NotUsed))
      .via(poolFlow)
      .map(_._1)
      .collect {
        case Success(response) => response
      }
  }

  def single = {
    Http().outgoingConnection(uri.authority.host.address(), port)
  }

}
