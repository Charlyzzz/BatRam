package carrier

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.scaladsl.SourceQueueWithComplete
import carrier.JetCommands._

import scala.concurrent.duration.FiniteDuration

object Radar {

  trait RadarCommands

  case class ReceivedJetPosition(position: Position) extends RadarCommands

  object UpdateJetsPosition extends RadarCommands

  case class JetsListing(jets: Receptionist.Listing) extends RadarCommands

  case class ChangeFireRate(requestPerSecond: Int) extends RadarCommands

  case class Position(id: String, hits: Int, misses: Int)

  def apply(tickInterval: FiniteDuration, downlink: SourceQueueWithComplete[Position]): Behavior[RadarCommands] = Behaviors.withTimers[RadarCommands] { timer =>
    Behaviors.setup { ctx =>
      val receptionist = ctx.system.receptionist

      val jetsListingAdapter = ctx.messageAdapter[Receptionist.Listing](JetsListing)
      receptionist ! Receptionist.subscribe(Jet.key, jetsListingAdapter)

      val jetPositionAdapter = ctx.messageAdapter[Position](ReceivedJetPosition)

      timer.startTimerAtFixedRate(UpdateJetsPosition, tickInterval)

      var activeJets: Set[ActorRef[JetCommand]] = Set()

      Behaviors.receiveMessage {
        case JetsListing(Jet.key.Listing(jets)) =>
          activeJets = jets
          Behaviors.same
        case UpdateJetsPosition =>
          activeJets.foreach(_ ! ReportAndClearPosition(jetPositionAdapter))
          Behaviors.same
        case ReceivedJetPosition(position) =>
          val correctedRates = position.copy(
            hits = position.hits / tickInterval.length.toInt,
            misses = position.misses / tickInterval.length.toInt
          )
          downlink.offer(correctedRates)
          Behaviors.same
        case ChangeFireRate(requestPerSecond) =>
          activeJets.foreach(_ ! FireRate(requestPerSecond))
          Behaviors.same
      }
    }
  }
}
