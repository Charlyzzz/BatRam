package carrier

import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorSystem, Behavior}
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.typed._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.stream.scaladsl.Source
import akka.stream.{Materializer, OverflowStrategy}
import carrier.Radar.{ChangeFireRate, Position}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Carrier extends App {

  val isWorker = sys.env.contains("WORKER")
  if (isWorker) {
    val masterAddress = sys.env.get("MASTER_ADDRESS")
    require(masterAddress.isDefined, "provide MASTER_ADDRESS")
    ActorSystem[Nothing](WorkerGuardian(masterAddress.get), "carrier")
  } else {
    ActorSystem[Nothing](MasterGuardian(), "carrier")
  }


}

trait GuardianBehavior {

  def spawnJet(ctx: ActorContext[_]): Unit = {
//    val maxConnections = sys.env.get("MC").collect(_.toInt).getOrElse(16384)
//    val maxOpenRequests = sys.env.get("OR").collect(_.toInt).getOrElse(32768)
//    val pipeliningLimit = sys.env.get("PL").collect(_.toInt).getOrElse(16)

    val poolSettings = ConnectionPoolSettings(ctx.system.toClassic)
//          .withMaxConnections(maxConnections)
//          .withMaxOpenRequests(maxOpenRequests)
//          .withPipeliningLimit(pipeliningLimit)

    val jetId = Cluster(ctx.system).selfMember.address.toString
    val http = Jet(jetId, "http://localhost:8082/")(poolSettings)
    ctx.spawn(http, "jet-1")
  }

  def prepareCluster(ctx: ActorContext[_]): Unit = {
    AkkaManagement(ctx.system).start()
    ClusterBootstrap(ctx.system).start()
    ctx.spawn(ClusterStateListener(), "ClusterListener")
  }
}

object MasterGuardian extends GuardianBehavior {

  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing] { ctx =>

    implicit val materializer: Materializer = Materializer(ctx.system.toClassic)
    implicit val executionContext: ExecutionContext = ctx.executionContext

    prepareCluster(ctx)

    val (positionsSink, rateOfFire, serverBinding) = ManagementServer(80)(ctx.system.toClassic)

    serverBinding.onComplete {
      case Success(_) =>
        val (positionsDownlink, positionsSource) = Source.queue[Position](1024, OverflowStrategy.dropTail).preMaterialize

        val radar = ctx.spawn(Radar(1.seconds, positionsDownlink), "radar")

        rateOfFire.runForeach(radar ! ChangeFireRate(_))

        positionsSource.runWith(positionsSink)

        spawnJet(ctx)

      case Failure(exception) =>
        ctx.system.log.error("", exception)
        ctx.system.terminate()
    }

    Behaviors.ignore
  }
}


object WorkerGuardian extends GuardianBehavior {

  val serviceKey: ServiceKey[Int] = ServiceKey("meters")


  def apply(masterAddress: String): Behavior[Nothing] = Behaviors.setup[Nothing] { ctx =>
    prepareCluster(ctx)
    spawnJet(ctx)
    Behaviors.ignore
  }
}

object ClusterStateListener {
  def apply(): Behavior[MemberEvent] = Behaviors.setup { ctx =>
    Cluster(ctx.system).subscriptions ! Subscribe(ctx.self, classOf[MemberEvent])
    Behaviors.logMessages(Behaviors.ignore)
  }
}