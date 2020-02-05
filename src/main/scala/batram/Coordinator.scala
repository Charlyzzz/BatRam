package batram

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import rampup.Handler._

object Coordinator {

  trait Protocol

  case class ReceptionistListing(actorListings: Listing) extends Protocol

  trait Message extends Protocol

  case class F(n: Int) extends Message

  def apply(): Behavior[Message] = Behaviors.setup[Protocol] { ctx =>
    val receptionistAdapter = ctx.messageAdapter[Listing](ReceptionistListing)
    ctx.system.receptionist ! Receptionist.subscribe(serviceKey, receptionistAdapter)

    def coordinator(x: Set[ActorRef[Int]]): Behavior[Protocol] = Behaviors.receiveMessage {
      case ReceptionistListing(serviceKey.Listing(rams)) => coordinator(rams)
      case F(n) =>
        x.foreach(_ ! n)
        Behaviors.same
    }

    coordinator(Set.empty)
  }.narrow
}
