
import DynamicThrottle.Update
import ThroughputMeter.{ComputeError, ComputeSuccess}
import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object HttpRam {

  def apply(host: String, port: Int, uri: String): Behavior[Int] = Behaviors.setup { ctx =>
    implicit val mat: Materializer = Materializer(ctx)
    implicit val classicSystem: ActorSystem = ctx.system.toClassic

    val throughputMeter = ctx.spawnAnonymous(ThroughputMeter(5.seconds))
    val pool = Http().cachedHostConnectionPool[NotUsed](host, port, poolSettings(ctx))

    val throttler = ctx.spawnAnonymous(DynamicThrottle.behavior)

    val throttlePeriod = 1.second
    Source.repeat(NotUsed)
        .map((HttpRequest(uri = uri), _))
        .via(new DynamicThrottle(1000, throttlePeriod)(throttler))
        .via(pool)
        .map(_._1)
        .map {
          case Success(_) => ComputeSuccess
          case Failure(_) => ComputeError
        }
        .runForeach(throughputMeter ! _)

    Behaviors.receiveMessage { rps =>
      throttler ! Update(rps, throttlePeriod)
      Behaviors.same
    }
  }

  private def poolSettings(ctx: ActorContext[_]) = {
    val maxConnections = sys.env.get("MC").collect(_.toInt).getOrElse(512)
    val maxOpenRequests = sys.env.get("OR").collect(_.toInt).getOrElse(16384)
    val pipeliningLimit = sys.env.get("PL").collect(_.toInt).getOrElse(10)

    ctx.log.info(s"maxConnections=$maxConnections, maxOpenRequests=$maxOpenRequests, pipeliningLimit=$pipeliningLimit")
    ConnectionPoolSettings(ctx.system.toClassic)
        .withMaxConnections(maxConnections)
        .withMaxOpenRequests(maxOpenRequests)
        .withPipeliningLimit(pipeliningLimit)
  }
}