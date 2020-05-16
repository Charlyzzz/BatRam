package rampup

import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import rampup.JetControl.serviceKey


object JetControl {
  val serviceKey: ServiceKey[Int] = ServiceKey("meters")
}

object Manual {

  def apply(throttler: ActorRef[Int]): Behavior[Int] = Behaviors.setup { ctx =>
    ctx.system.receptionist ! Receptionist.register(serviceKey, ctx.self)
    Behaviors.receiveMessage {
      rps =>
        throttler ! rps
        Behaviors.same
    }
  }
}
