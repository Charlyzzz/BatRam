package carrier

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{NotUsed, actor => classicActor}
import flow.DynamicThrottle.{DynamicThrottleMessage, Update}
import flow.{AggregateStats, DynamicThrottle}

import scala.concurrent.duration._

trait XXX

object SuccessfulRequest extends XXX

object FailedRequest extends XXX

object CheckSpeed extends XXX

trait FeedbackLoop

trait Modifier extends FeedbackLoop {
  val value: Int
}

object Up extends Modifier {
  val value: Int = 100
}

object Down extends Modifier {
  val value: Int = -100
}

object Keep extends FeedbackLoop

object Requester {

  def apply(uri: Uri, desired: Int): Behavior[XXX] = Behaviors.setup { ctx =>

    val address = uri.authority.host.address()
    val port = uri.authority.port

    implicit val classicActorSystem: classicActor.ActorSystem = ctx.toClassic.system
    implicit val mat: Materializer = Materializer(classicActorSystem)
    val poolSettings = ConnectionPoolSettings(ctx.system.toClassic)
      .withMaxConnections(8)
      .withMaxOpenRequests(2048 * 64)
    val pool = Http().cachedHostConnectionPool[Long](address, port, poolSettings)
    val throttler: ActorRef[DynamicThrottleMessage] = ctx.spawnAnonymous(DynamicThrottle.behavior)
    var current = 100
    Source.repeat(NotUsed)
      .map(_ => (HttpRequest(uri = uri), System.currentTimeMillis()))
      .via(new DynamicThrottle(current)(throttler))
      .via(pool)
      .map {
        case (response, startedTimestamp) =>
          val elapsedTime = System.currentTimeMillis() - startedTimestamp
          response.foreach(_.discardEntityBytes())
          (response, elapsedTime)
      }
      .via(new AggregateStats(1.second))
      .wireTap(println(_))
      .grouped(3)
      .map { stats =>
        if (stats.forall(_.requestCount < desired)) {
          Up
        } else if (stats.forall(_.requestCount > desired)) {
          Down
        } else {
          Keep
        }
      }
      .wireTap(println(_))
      .runForeach {
        case a: Modifier =>
          current += a.value
          throttler.tell(Update(current))
      }
    Behaviors.empty
  }
}
