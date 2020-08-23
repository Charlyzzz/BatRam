package test

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import carrier.{Requester, XXX}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future

object Test extends App {
  Thread.sleep(20000)
  val uri = Uri("http://localhost:8082")
  implicit val system: ActorSystem[XXX] = ActorSystem(Requester(uri, 2000), "test", ConfigFactory.empty)
}
