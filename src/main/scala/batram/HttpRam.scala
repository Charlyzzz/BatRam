package batram

import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import batram.DynamicThrottle.Message
import batram.ThroughputMeter.{ComputeError, ComputeSuccess}
import rampup.Linear.Target
import rampup.{Linear, Manual}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object HttpRam {

  def apply(host: String, port: Int, uri: String): Behavior[Int] = Behaviors.setup { ctx =>
    implicit val mat: Materializer = Materializer(ctx)
    implicit val classicSystem: ActorSystem = ctx.system.toClassic

    val eventSink = Sink.ignore

    val throttlePeriod = 1.second

    val throttler: ActorRef[Message] = ctx.spawnAnonymous(DynamicThrottle.behavior)

    val statsActor = ctx.spawn(Stats.behavior(eventSink), "Stats")

    val linearRampUp = ctx.spawn(Linear(20, 5.minutes)(throttler), "LinearRampUp")
    val manualRampUp = ctx.spawn(Manual()(throttler), "ManualRampUp")

    val throughputMeter = ctx.spawnAnonymous(ThroughputMeter(1.seconds, statsActor))

    val pool = Http().cachedHostConnectionPool[NotUsed](host, port, poolSettings(ctx))

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
      //linearRampUp ! Target(rps)
      manualRampUp ! Target(rps)
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
      .withMinConnections(maxConnections / 2)
      .withMaxOpenRequests(maxOpenRequests)
      .withPipeliningLimit(pipeliningLimit)
  }
}
