import akka.actor
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, StreamConverters}
import batram.HttpRam
import com.typesafe.config.ConfigFactory
import rampup.{Linear, Manual}

import scala.concurrent.duration._

object Demo extends App {

  val demo = Behaviors.setup[Nothing] { ctx =>

    implicit val system: ActorSystem[Nothing] = ctx.system
    implicit val classicSystem: actor.ActorSystem = ctx.system.toClassic
    implicit val materializer: Materializer = Materializer(classicSystem)

    val maxConnections = sys.env.get("MC").collect(_.toInt).getOrElse(10000)
    val maxOpenRequests = sys.env.get("OR").collect(_.toInt).getOrElse(32768)
    val pipeliningLimit = sys.env.get("PL").collect(_.toInt).getOrElse(16)
    ctx.log.info(s"maxConnections=$maxConnections, maxOpenRequests=$maxOpenRequests, pipeliningLimit=$pipeliningLimit")

    val poolSettings = ConnectionPoolSettings(classicSystem)
      .withMaxConnections(maxConnections)
      .withMaxOpenRequests(maxOpenRequests)
      .withPipeliningLimit(pipeliningLimit)

    val httpRam = ctx.spawn(
      HttpRam("127.0.0.1", 3000, "http://127.0.0.1:3000/hi")(poolSettings, Sink.ignore),
      "ram"
    )

    val linearRampUp = ctx.spawn(Linear(20, 5.minutes)(httpRam), "LinearRampUp")
    val manualRampUp = ctx.spawn(Manual(httpRam), "ManualRampUp")

    StreamConverters.fromInputStream(() => System.in)
      .collect(_.utf8String.filter(_ >= ' ').toInt)
      .filter(_ >= 0)
      .runForeach(manualRampUp ! _)

    Behaviors.ignore
  }

  ActorSystem[Nothing](demo, "Demo", ConfigFactory.empty)
}
