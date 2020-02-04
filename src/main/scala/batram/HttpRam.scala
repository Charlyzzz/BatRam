package batram

import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import batram.DynamicThrottle.{Message, Update}
import batram.ThroughputMeter.{ComputeError, ComputeSuccess}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object HttpRam {

  def apply(host: String, port: Int, uri: String)
           (poolSettings: ConnectionPoolSettings, eventSink: Sink[ServerSentEvent, _]): Behavior[Int] =
    Behaviors.setup { ctx =>
      implicit val mat: Materializer = Materializer(ctx)
      implicit val classicSystem: ActorSystem = ctx.system.toClassic

      val throttlePeriod = 1.second

      val throttler: ActorRef[Message] = ctx.spawnAnonymous(DynamicThrottle.behavior)

      val statsActor = ctx.spawn(Stats.behavior(eventSink), "Stats")

      val throughputMeter = ctx.spawnAnonymous(ThroughputMeter(1.seconds, statsActor))

      val pool = Http().cachedHostConnectionPool[NotUsed](host, port, poolSettings)

      Source.repeat(NotUsed)
        .map((HttpRequest(uri = uri), _))
        .via(new DynamicThrottle(0, throttlePeriod)(throttler))
        .via(pool)
        .map(_._1)
        .map {
          case Success(_) => ComputeSuccess
          case Failure(_) => ComputeError
        }
        .runForeach(throughputMeter.tell)

      Behaviors.receiveMessage { rps =>
        throttler ! Update(rps, 1.second)
        Behaviors.same
      }
    }
}
