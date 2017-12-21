package wormly

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}

object GameClient {

  case class OutputActor(outputActor: ActorRef)

  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props =
    Props(new GameClient(gameCycle, sequentialOperationsManager))

}

class GameClient(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {
  import GameClient._

  def mapVisibleObjects(in: SequentialOperationsManager.VisibleObjects): ConnectionHandler.VisibleObjectsOut = {
    ConnectionHandler.VisibleObjectsOut(in.snakeParts.flatMap(mapSnakePart), in.food.map(mapFood), in.offsetY, in.offsetX)
  }

  def mapFood(in: SequentialOperationsManager.Food): ConnectionHandler.FoodOut = {
    ConnectionHandler.FoodOut(in.y, in.x, in.d, Utils.colorToString(in.color))
  }

  def mapSnakePart(in: SequentialOperationsManager.VisibleParts): List[ConnectionHandler.SnakePartOut] = {
    in.snakeParts.foldLeft((in.color.darker(), List.empty[ConnectionHandler.SnakePartOut])) { case ((color, accumulator), part) =>
      val brighterColor = color.brighter()
      (brighterColor, ConnectionHandler.SnakePartOut(part.y, part.x, in.size, Utils.colorToString(brighterColor)) :: accumulator)
    }._2
  }

  override def receive: Receive = receiveWithNoOutput

  def receiveGameStarted(snakeState: ActorRef, output: ActorRef): Receive = {
    case GameCycle.Tick =>
      log.debug("Tick. Snake update")
      snakeState ! Snake.Update

    case s @ Snake.SnakeState(_, _, _) =>
      log.debug("Snake state updated: {}", s)
      sequentialOperationsManager ! s

    case SequentialOperationsManager.Collision =>
      log.debug("Collision")
      snakeState ! PoisonPill

    case SequentialOperationsManager.Feeding(value) =>
      log.debug("Feeding for {}", value)
      snakeState ! Snake.IncreaseSize(value)

    case vo @ SequentialOperationsManager.VisibleObjects(_, _, _, _) =>
      log.debug("Visible objects: {}", vo)
      output ! mapVisibleObjects(vo)

    case ConnectionHandler.CursorPositionIn(angle) =>
      log.debug("CursorPositionIn: {}", angle)
      snakeState ! Snake.ChangeAngle(angle)

    case Terminated(target) if target == snakeState =>
      log.debug("Terminated: {}", target)
      output ! ConnectionHandler.CollisionOut()
      gameCycle ! GameCycle.UnmanageMe
      context.unwatch(target)
      context.become(receiveGameNotStarted(output), discardOld = true)

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }

  def receiveGameNotStarted(output: ActorRef): Receive = {
    case ConnectionHandler.StartGameIn(_) =>
      log.debug("Starting game")
      val snakeState = context.actorOf(Snake.props())
      gameCycle ! GameCycle.ManageMe
      sequentialOperationsManager ! SequentialOperationsManager.NewSnake
      context.watch(snakeState)
      context.become(receiveGameStarted(snakeState, output), discardOld = true)

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }

  val receiveWithNoOutput: Receive = {
    case OutputActor(outputActor) =>
      context.become(receiveGameNotStarted(outputActor), discardOld = true)

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}
