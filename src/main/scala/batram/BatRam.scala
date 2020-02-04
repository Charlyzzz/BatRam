package batram

import akka.actor.AddressFromURIString
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.typed._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, StreamConverters}
import batram.Coordinator.F
import rampup.{Handler, Manual}

object BatRam extends App {

  val isMaster = sys.env.get("MASTER").isDefined
  if (isMaster)
    ActorSystem[Nothing](MasterGuardian(), "batram-cluster")
  else {
    val masterAddress = sys.env.get("MASTER_ADDRESS")
    require(masterAddress.isDefined, "provide MASTER_ADDRESS")
    ActorSystem[Nothing](WorkerGuardian(masterAddress.get), "batram-cluster")
  }
}

trait GuardianBehavior {

  def spawnCoordinator(ctx: ActorContext[_]): ActorRef[Coordinator.Message] = {
    val supervisedCoordinator = Behaviors.supervise(Coordinator()).onFailure[Exception](SupervisorStrategy.restart)
    ClusterSingleton(ctx.system)
      .init(SingletonActor(supervisedCoordinator, "Coordinator"))
  }

  def spawnRam(ctx: ActorContext[_], eventSink: Sink[ServerSentEvent, _] = Sink.ignore): Unit = {
    val maxConnections = sys.env.get("MC").collect(_.toInt).getOrElse(16384)
    val maxOpenRequests = sys.env.get("OR").collect(_.toInt).getOrElse(32768)
    val pipeliningLimit = sys.env.get("PL").collect(_.toInt).getOrElse(16)

    val poolSettings = ConnectionPoolSettings(ctx.system.toClassic)
      .withMaxConnections(maxConnections)
      .withMaxOpenRequests(maxOpenRequests)
      .withPipeliningLimit(pipeliningLimit)

    val httpRam = ctx.spawn(
      HttpRam("127.0.0.1", 3000, "http://127.0.0.1:3000/hi")(poolSettings, eventSink),
      "ram"
    )

    val manualRampUp = ctx.spawn(Manual(httpRam), "ManualRampUp")
    ctx.system.receptionist ! Receptionist.register(Handler.serviceKey, manualRampUp)
  }

  def prepareCluster(ctx: ActorContext[_]): Cluster = {
    ctx.spawn(ClusterStateListener(), "ClusterListener")
    Cluster(ctx.system)
  }
}

object MasterGuardian extends GuardianBehavior {

  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing] { ctx =>

    implicit val materializer: Materializer = Materializer(ctx.system.toClassic)

    prepareCluster(ctx)
    val eventSink = StatsServer(80)(ctx.system.toClassic)
    spawnRam(ctx, eventSink)

    val coordinator = spawnCoordinator(ctx)

    StreamConverters.fromInputStream(() => System.in)
      .collect(_.utf8String.filter(_ >= ' ').toInt)
      .filter(_ >= 0)
      .runForeach(coordinator ! F(_))

    Behaviors.ignore
  }
}


object WorkerGuardian extends GuardianBehavior {

  val serviceKey: ServiceKey[Int] = ServiceKey("meters")

  def apply(masterAddress: String): Behavior[Nothing] = Behaviors.setup[Nothing] { ctx =>

    val cluster = prepareCluster(ctx)
    cluster.manager ! Join(AddressFromURIString(s"akka://batram-cluster@$masterAddress"))

    spawnRam(ctx)

    Behaviors.ignore
  }
}

object ClusterStateListener {
  def apply(): Behavior[MemberEvent] = Behaviors.setup { ctx =>
    Cluster(ctx.system).subscriptions ! Subscribe(ctx.self, classOf[MemberEvent])
    Behaviors.logMessages(Behaviors.ignore)
  }
}