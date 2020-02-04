package batram

import akka.actor.typed.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.StreamConverters
import com.typesafe.config.ConfigFactory

object Demo extends App {


  val httpRam = HttpRam("10.0.1.5", 3000, "http://10.0.1.5:3000/hi")
  val system = ActorSystem(httpRam, "HttpRam", ConfigFactory.empty)
  implicit val materializer: Materializer = Materializer(system)

  StreamConverters.fromInputStream(() => System.in)
    .collect(_.utf8String.filter(_ >= ' ').toInt)
    .filter(_ >= 0)
    .runForeach(system.tell)
}
