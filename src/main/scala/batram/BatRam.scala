package batram

import akka.actor.CoordinatedShutdown.UnknownReason
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.actor.{AddressFromURIString, CoordinatedShutdown}
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.typed._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.scaladsl.{Sink, StreamRefs}
import akka.stream.{Materializer, SinkRef}
import akka.util.Timeout
import batram.Coordinator.{F, GetEventSink, SetEventSink}
import rampup.{Handler, Manual}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

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

  def spawnRam(ctx: ActorContext[_], eventSink: Sink[Stats, _] = Sink.ignore): Unit = {
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
    implicit val executionContext: ExecutionContext = ctx.executionContext

    val (eventSink, rateSource, serverBinding) = ManagementServer(80)(ctx.system.toClassic)

    serverBinding.onComplete {
      case Success(_) =>

        prepareCluster(ctx)

        val remoteSink = StreamRefs.sinkRef[Stats]().to(eventSink).run()

        val coordinator = spawnCoordinator(ctx)
        coordinator ! SetEventSink(remoteSink)
        spawnRam(ctx, eventSink)

        rateSource
          .filter(_ >= 0)
          .runForeach(coordinator ! F(_))

      case Failure(exception) =>
        ctx.system.log.error("", exception)
        CoordinatedShutdown(ctx.system).run(UnknownReason)
    }

    Behaviors.ignore
  }
}


object WorkerGuardian extends GuardianBehavior {

  val serviceKey: ServiceKey[Int] = ServiceKey("meters")

  def apply(masterAddress: String): Behavior[Nothing] = Behaviors.setup[SinkRef[Stats]] { ctx =>
    implicit val materializer: Materializer = Materializer(ctx.system)
    implicit val timeout: Timeout = Timeout(15.second)
    implicit val executionContext: ExecutionContext = ctx.executionContext
    val cluster = prepareCluster(ctx)
    cluster.manager ! Join(AddressFromURIString(s"akka://batram-cluster@$masterAddress"))
    val coordinator = spawnCoordinator(ctx)

    ctx.ask[GetEventSink, SinkRef[Stats]](coordinator, replyTo => GetEventSink(replyTo)) {
      case Success(value) => value
    }

    Behaviors.receiveMessage {
      sinkRef =>
        spawnRam(ctx, sinkRef.sink())
        Behaviors.ignore
    }
  }.narrow
}

object ClusterStateListener {
  def apply(): Behavior[MemberEvent] = Behaviors.setup { ctx =>
    Cluster(ctx.system).subscriptions ! Subscribe(ctx.self, classOf[MemberEvent])
    Behaviors.logMessages(Behaviors.ignore)
  }
}