import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.scaladsl.Source
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object PoolTest extends App {
  val uri = Uri("http://localhost:8082")
  implicit val system = ActorSystem("pool", ConfigFactory.empty())
  val address = uri.authority.host.address()
  val port = uri.authority.port

  class Tracker extends Actor with ActorLogging {
    var requestCount = 0


    override def aroundPreStart(): Unit = {
      context.system.scheduler.scheduleAtFixedRate(0.seconds, 1.second)(() => self ! "reset")(context.dispatcher)
    }

    override def receive: Receive = {
      case "track" =>
        requestCount += 1
      case "reset" =>
        log.info(requestCount.toString)
        requestCount = 0
    }
  }
  val tracker = system.actorOf(Props[Tracker])


//  val maxConnections = sys.env.get("MC").collect(_.toInt).getOrElse(8096)
//  val maxOpenRequests = sys.env.get("OR").collect(_.toInt).getOrElse(32768)
//  val pipeliningLimit = sys.env.get("PL").collect(_.toInt).getOrElse(16)
  val poolSettings = ConnectionPoolSettings(system)
//    .withMaxConnections(maxConnections)
//    .withMaxOpenRequests(maxOpenRequests)
//    .withPipeliningLimit(pipeliningLimit)

  val pool = Http().cachedHostConnectionPool[NotUsed](address, port, poolSettings)

  Source.repeat(NotUsed)
    .map((HttpRequest(uri = uri), _))
    .throttle(15000, 1.second)
    .via(pool)
    .map(_._1)
    .runForeach {

      case Success(response) =>
        tracker ! "track"
        response.discardEntityBytes()
      case Failure(_) =>
    }

}
