import akka.actor
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.Materializer
import carrier.Jet
import com.typesafe.config.ConfigFactory

object Demo extends App {

  val demo = Behaviors.setup[Nothing] { ctx =>

    implicit val system: ActorSystem[Nothing] = ctx.system
    implicit val classicSystem: actor.ActorSystem = ctx.system.toClassic
    implicit val materializer: Materializer = Materializer(classicSystem)

    val maxConnections = sys.env.get("MC").collect(_.toInt).getOrElse(10000)
    val maxOpenRequests = sys.env.get("OR").collect(_.toInt).getOrElse(32768)
    val pipeliningLimit = sys.env.get("PL").collect(_.toInt).getOrElse(16)
    ctx.log.info(s"maxConnections=$maxConnections, maxOpenRequests=$maxOpenRequests, pipeliningLimit=$pipeliningLimit")

    val poolSettings = ConnectionPoolSettings(classicSystem)
      .withMaxConnections(maxConnections)
      .withMaxOpenRequests(maxOpenRequests)
      .withPipeliningLimit(pipeliningLimit)

    val jetBehavior = Jet("1", "d44mo8z3ie.execute-api.us-east-2.amazonaws.com", 443, "https://d44mo8z3ie.execute-api.us-east-2.amazonaws.com/dev?statusCode=502")(poolSettings)

    val jet = ctx.spawn(jetBehavior, "jet")

    Behaviors.ignore
  }

  ActorSystem[Nothing](demo, "Demo", ConfigFactory.empty)
}
