
import DynamicThrottle.Update
import ThroughputMeter.{ComputeError, ComputeSuccess}
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{NotUsed, actor => classic}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object HttpRam {

  def apply(): Behavior[Int] = Behaviors.setup { ctx =>
    implicit val mat: Materializer = Materializer(ctx)
    implicit val classicSystem: classic.ActorSystem = ctx.system.toClassic

    val throughputMeter = ctx.spawnAnonymous(ThroughputMeter(5.seconds))
    val pool = Http().cachedHostConnectionPool[NotUsed]("localhost", 3000)

    val throttler = ctx.spawnAnonymous(DynamicThrottle.behavior)

    val throttlePeriod = 1.second
    Source.repeat(NotUsed)
      .map((HttpRequest(uri = "http://localhost:3000/hi"), _))
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
}