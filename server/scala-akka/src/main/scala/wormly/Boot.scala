package wormly

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContextExecutor
import scala.io.StdIn

object Boot extends App {
  import akka.http.scaladsl.server.Directives._

  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  val config = system.settings.config
  val interface = config.getString("application.http-binding.address")
  val port = config.getInt("application.http-binding.port")

  val gameCycle = system.actorOf(GameCycle.props(), Utils.actorName(GameCycle.getClass))
  val sequentialOperationsManager = system.actorOf(SequentialOperationsManager.props(), Utils.actorName(SequentialOperationsManager.getClass))
  val testSnakes = system.actorOf(TestSnakes.props(gameCycle, sequentialOperationsManager), Utils.actorName(TestSnakes.getClass))

  val route: Route = {
    pathSingleSlash {
      getFromResource("index.html")
    } ~
      path("wormly") {
        handleWebSocketMessages(ConnectionHandler.createActorHandlingFlow(gameCycle, sequentialOperationsManager))
      }
  }

  val bindingFuture = Http().bindAndHandle(route, interface, port)

  println(s"Server online at http://$interface:$port/\nPress RETURN to stop...")
  StdIn.readLine()

  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => system.terminate())
}
