package wormly

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import upickle.default._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object ConnectionHandler {
  sealed trait WsIncoming
  case class StartGameIn(name: String) extends WsIncoming
  case class CanvasSize(height: Double, width: Double) extends WsIncoming
  case class CursorPositionIn(angle: Double) extends WsIncoming // radians

  sealed trait WsOutgoing
  case class FoodOut(y: Int, x: Int, d: Int, color: String)
  case class SnakePartOut(y: Int, x: Int, d: Int, color: String)
  case class VisibleObjectsOut(snakeParts: List[SnakePartOut], food: Set[FoodOut],
                               ver: List[Int], hor: List[Int], y: Int, x: Int) extends WsOutgoing
  case class CollisionOut() extends WsOutgoing

  def createActorHandlingFlow(gameCycle: ActorRef, sequentialOperationsManager: ActorRef)
                             (implicit system: ActorSystem, materializer: ActorMaterializer, ec: ExecutionContext): Flow[Message, Message, Any] = {
    val gameClient = system.actorOf(GameClient.props(gameCycle, sequentialOperationsManager), Utils.actorName(GameClient.getClass))

    val incomingMessages: Sink[Message, NotUsed] =
      Flow[Message].map {
        case tm: TextMessage => tm.textStream
      }.mapAsync(4) { partsOfInput =>
        partsOfInput
          .runFold("")(_ + _)
          .flatMap { completeInput =>
            Future.fromTry(Try{read[WsIncoming](completeInput)})
          }
      }.to(Sink.actorRef[WsIncoming](gameClient, PoisonPill))

    val outgoingMessages: Source[Message, NotUsed] =
      Source.actorRef[WsOutgoing](10, OverflowStrategy.fail)
        .mapMaterializedValue { outActor =>
          gameClient ! GameClient.OutputActor(outActor)
          NotUsed
        }.map((outMsg: WsOutgoing) => TextMessage(write(outMsg)))

    Flow.fromSinkAndSource(incomingMessages, outgoingMessages)
  }
}
