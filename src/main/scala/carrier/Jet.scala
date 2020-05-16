package carrier

import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import carrier.JetCommands._
import carrier.Radar.Position
import flow.DynamicThrottle
import flow.DynamicThrottle.{DynamicThrottleMessage, Update}

import scala.concurrent.duration._
import scala.util.{Failure, Success}


object JetCommands {

  trait JetCommand

  object Engaged extends JetCommand

  object Hit extends JetCommand

  object Miss extends JetCommand

  case class ReportAndClearPosition(replyTo: ActorRef[Position]) extends JetCommand

  case class FireRate(requestPerSecond: Int) extends JetCommand

}

object Jet  {

  val key: ServiceKey[JetCommand] = ServiceKey("jets")

  def apply(id: String, host: String, port: Int, uri: String)(poolSettings: ConnectionPoolSettings): Behavior[JetCommand] =
    Behaviors.setup { ctx =>
      implicit val jetId: String = id

      implicit val mat: Materializer = Materializer(ctx)
      implicit val classicSystem: ActorSystem = ctx.system.toClassic
      ctx.system.receptionist ! Receptionist.register(Jet.key, ctx.self)

      val throttlePeriod = 1.second

      implicit val throttler: ActorRef[DynamicThrottleMessage] = ctx.spawnAnonymous(DynamicThrottle.behavior)

      val pool = Http().cachedHostConnectionPool[NotUsed](host, port, poolSettings)

      Source.repeat(NotUsed)
        .map((HttpRequest(uri = uri), _))
        .via(new DynamicThrottle(0, throttlePeriod)(throttler))
        .via(pool)
        .map(_._1)
        .map {
          case Success(_) => Hit
          case Failure(_) => Miss
        }
        .runForeach(ctx.self ! _)

      attacking(0, 0)
    }

  def attacking(hits: Int, misses: Int)(implicit id: String, throttler: ActorRef[DynamicThrottleMessage]): Behavior[JetCommand] = Behaviors.receiveMessagePartial {
    case Hit =>
      attacking(hits + 1, misses)
    case Miss =>
      attacking(hits, misses + 1)
    case ReportAndClearPosition(radar) =>
      radar ! Position(id, hits, misses)
      attacking(0, 0)
    case FireRate(requestPerSecond) =>
      throttler ! Update(requestPerSecond)
      Behaviors.same
  }

}
