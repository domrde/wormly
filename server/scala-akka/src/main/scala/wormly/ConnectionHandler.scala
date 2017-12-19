package wormly

import akka.NotUsed
import akka.actor.{ActorSystem, PoisonPill}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import upickle.default.{read, write}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object ConnectionHandler {

  sealed trait WsIncoming

  case class StartGame(name: String) extends WsIncoming

  case class CursorPosition(angle: Double) extends WsIncoming

  sealed trait WsOutgoing

  case class Food(y: Double, x: Double, color: String, value: Double)

  case class SnakePart(y: Double, x: Double, r: Double, color: String)

  case class VisibleObjects(snakeParts: List[SnakePart], food: List[Food]) extends WsOutgoing

  def flow(implicit system: ActorSystem, materializer: ActorMaterializer, ec: ExecutionContext): Flow[Message, Message, Any] = {
    val gameClient = system.actorOf(GameClient.props())

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
