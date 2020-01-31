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
import akka.stream.scaladsl.{Sink, Source}
import akka.{NotUsed, actor => classic}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object BatRam extends App {
  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](BatRamSystem(), "batram-cluster")
  implicit val classicSystem: classic.ActorSystem = system.toClassic
  implicit val materializer = Materializer(system)

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
        case (Success(_), i) => println(s"[${ LocalDateTime.now }] $i succeeded")
        case (Failure(e), i) => println(s"[${ LocalDateTime.now }] $i failed: $e")
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

  object Contar

  def rater(time: Int): Behavior[Any] = Behaviors.withTimers { timer =>
    Behaviors.setup[Any] { ctx =>
      timer.startTimerWithFixedDelay(Contar, time.seconds)

      def counting(n: Int): Behavior[Any] = Behaviors.receiveMessage {
        case Contar =>
          ctx.log.info(s"${ n / time } req/s")
          counting(0)
        case a => counting(n + 1)
      }

      counting(0)
    }
  }

  val systemBehavior = Behaviors.setup[Nothing] { ctx =>
    val raterActor = ctx.spawnAnonymous(rater(3))
    implicit val mat = Materializer(ctx)
    implicit val classicSystem = ctx.system.toClassic
    val poolSettings: ConnectionPoolSettings = ConnectionPoolSettings(classicSystem).withMaxConnections(1024).withMaxOpenRequests(4096).withPipeliningLimit(2)
    val pool = Http().cachedHostConnectionPool[Int]("localhost", 3000, poolSettings)

    Source.repeat(NotUsed)
        .map(_ => (HttpRequest(uri = "http://localhost:3000/hi"), 1))
        .via(pool)
        .runWith(Sink.actorRef(raterActor.toClassic, NotUsed, _ => NotUsed))

    Behaviors.ignore
  }

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](systemBehavior, "foo", ConfigFactory.empty())
}