package wormly

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}

object ServiceSnakes {
  sealed trait SnakeType
  case object FieldRangeSnakeType extends SnakeType

  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props = Props(new ServiceSnakes(gameCycle, sequentialOperationsManager))
}

class ServiceSnakes(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {
  import ServiceSnakes._

  override def receive: Receive = receiveWithSnakes(Map(createServiceSnake(FieldRangeSnakeType) -> FieldRangeSnakeType))

  def createServiceSnake(snakeType: SnakeType): ActorRef = {
    snakeType match {
      case FieldRangeSnakeType =>
        val snake = context.actorOf(FieldRangeSnake.props(gameCycle, sequentialOperationsManager), Utils.actorName(FieldRangeSnake.getClass))
        context.watch(snake)
        snake
    }
  }

  def receiveWithSnakes(snakes: Map[ActorRef, SnakeType]): Receive = {
    case Terminated(target) =>
      context.unwatch(target)
      val snakeType = snakes(target)
      val snake = createServiceSnake(snakeType)
      val newSnakes = (snakes - target) + (snake -> snakeType)
      context.become(receiveWithSnakes(newSnakes), discardOld = true)

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}

object FieldRangeSnake {
  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props = Props(new FieldRangeSnake(gameCycle, sequentialOperationsManager))
}

class FieldRangeSnake(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {
  private val config = context.system.settings.config
  private val fieldHeight = config.getInt("application.game-field.height")
  private val fieldWidth = config.getInt("application.game-field.width")
  private val maxPartDiameter = config.getDouble("application.snake.maximum-part-diameter")
  private val distanceBetweenParts = config.getDouble("application.snake.distance-between-parts")

  private val gameClient = context.actorOf(GameClient.props(gameCycle, sequentialOperationsManager), Utils.actorName(GameClient.getClass))

  gameClient ! GameClient.OutputActor(self)
  gameClient ! ConnectionHandler.CanvasSize(5, 5)
  gameClient ! ConnectionHandler.StartGameIn("unused")

  private val length = Math.ceil((fieldHeight + fieldWidth) * 2.1 / distanceBetweenParts / maxPartDiameter).toInt
  log.info(s"border snake length is $length")
  gameClient ! GameClient.MoveSnakeImmediately(0, 0, -90.toRadians, maxPartDiameter, length)

  override def receive: Receive = {
    case ConnectionHandler.CollisionOut() =>
      gameClient ! PoisonPill
      self ! PoisonPill

    case ConnectionHandler.VisibleObjectsOut(_, _, _, _, y, x) if Math.hypot(y - 0, x - 0) < 10.0 =>
      sender() ! ConnectionHandler.CursorPositionIn(-90.toRadians)

    case ConnectionHandler.VisibleObjectsOut(_, _, _, _, y, x) if Math.hypot(y - fieldHeight, x - 0) < 10.0 =>
      sender() ! ConnectionHandler.CursorPositionIn(180.toRadians)

    case ConnectionHandler.VisibleObjectsOut(_, _, _, _, y, x) if Math.hypot(y - fieldHeight, x - fieldWidth) < 10.0 =>
      sender() ! ConnectionHandler.CursorPositionIn(90.toRadians)

    case ConnectionHandler.VisibleObjectsOut(_, _, _, _, y, x) if Math.hypot(y - 0, x - fieldWidth) < 10.0 =>
      sender() ! ConnectionHandler.CursorPositionIn(0.toRadians)

    case ConnectionHandler.VisibleObjectsOut(_, _, _, _, _, _) =>


    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }

}
