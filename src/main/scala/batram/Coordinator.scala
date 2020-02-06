package batram

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.SinkRef
import rampup.Handler._

object Coordinator {

  trait Protocol

  case class ReceptionistListing(actorListings: Listing) extends Protocol

  trait Message extends Protocol

  case class F(n: Int) extends Message

  case class GetEventSink(replyTo: ActorRef[SinkRef[Stats]]) extends Message

  case class SetEventSink(sinkRef: SinkRef[Stats]) extends Message

  def apply(): Behavior[Message] = Behaviors.setup[Protocol] { ctx =>
    val receptionistAdapter = ctx.messageAdapter[Listing](ReceptionistListing)
    ctx.system.receptionist ! Receptionist.subscribe(serviceKey, receptionistAdapter)

    def coordinator(x: Set[ActorRef[Int]], sinkRef: Option[SinkRef[Stats]]): Behavior[Protocol] = Behaviors.receiveMessage {
      case ReceptionistListing(serviceKey.Listing(rams)) => coordinator(rams, sinkRef)
      case F(n) =>
        x.foreach(_ ! n)
        Behaviors.same
      case GetEventSink(replyTo) =>
        sinkRef.foreach(replyTo ! _)
        Behaviors.same
      case SetEventSink(sinkRef) =>
        coordinator(x, Some(sinkRef))
    }

    coordinator(Set.empty, None)
  }.narrow
}
