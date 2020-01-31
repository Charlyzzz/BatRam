import java.time.LocalDateTime

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.{Address, AddressFromURIString}
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.typed.{Cluster, JoinSeedNodes, Subscribe}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.{actor => classic}
import com.typesafe.config.ConfigFactory

import scala.util.{Failure, Success}

object BatRam extends App {
  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](BatRamSystem(), "batram-cluster")
  implicit val classicSystem: classic.ActorSystem = system.toClassic
  implicit val materializer: Materializer = Materializer(system)

  sys.addShutdownHook(system.terminate())

  val cluster = Cluster(system)

  val masterAddress: Address = AddressFromURIString("akka://batram-cluster@localhost:25520")
  if (cluster.selfMember.address != masterAddress)
    cluster.manager ! JoinSeedNodes(masterAddress :: Nil)

  private val poolSettings: ConnectionPoolSettings = ConnectionPoolSettings(classicSystem).withMaxConnections(64).withMaxOpenRequests(1024)
  val pool = Http().cachedHostConnectionPool[Int]("localhost", 8558, poolSettings)

  Source(1 to 100)
    .map((HttpRequest(uri = "localhost:8558"), _))
    .via(pool)
    .runWith(Sink.foreach {
      case (Success(_), i) => println(s"[${LocalDateTime.now}] $i succeeded")
      case (Failure(e), i) => println(s"[${LocalDateTime.now}] $i failed: $e")
    })
}

object BatRamSystem {
  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing] { ctx =>
    ctx.spawn(ClusterStateListener(), "ClusterListener")
    Behaviors.empty
  }.narrow

}

object ClusterStateListener {
  def apply(): Behavior[MemberEvent] = Behaviors.setup { ctx =>
    Cluster(ctx.system).subscriptions ! Subscribe(ctx.self, classOf[MemberEvent])
    Behaviors.logMessages(Behaviors.ignore)
  }
}


object A extends App {
  val system = ActorSystem(HttpRam(), "HttpRam", ConfigFactory.empty)
  implicit val materializer: Materializer = Materializer(system)

  StreamConverters.fromInputStream(() => System.in)
    .collect(_.utf8String.filter(_ >= ' ').toInt)
    .runForeach(system.tell)
}