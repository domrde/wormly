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
    ConnectionHandler.VisibleObjectsOut(in.snakeParts.flatMap(mapSnakePart), in.food.map(mapFood))
  }

  def mapFood(in: SequentialOperationsManager.Food): ConnectionHandler.FoodOut = {
    ConnectionHandler.FoodOut(in.y, in.x, Utils.colorToString(in.color), in.value)
  }

  def mapSnakePart(in: SequentialOperationsManager.VisibleParts): List[ConnectionHandler.SnakePartOut] = {
    in.snakeParts.map(part => ConnectionHandler.SnakePartOut(part.y, part.x, in.size, Utils.colorToString(in.color)))
  }

  override def receive: Receive = receiveWithNoOutput

  def receiveGameStarted(snakeState: ActorRef, output: ActorRef): Receive = {
    case GameCycle.Tick =>
      snakeState ! Snake.Update

    case s @ Snake.SnakeState(_, _, _) =>
      sequentialOperationsManager ! s

    case SequentialOperationsManager.Collision =>
      snakeState ! PoisonPill

    case SequentialOperationsManager.Feeding(value) =>
      snakeState ! Snake.IncreaseSize(value)

    case vo @ SequentialOperationsManager.VisibleObjects(_, _) =>
      output ! mapVisibleObjects(vo)

    case ConnectionHandler.CursorPositionIn(angle) =>
      snakeState ! Snake.ChangeAngle(angle)

    case Terminated(target) if target == snakeState =>
      output ! ConnectionHandler.CollisionOut
      context.become(receiveGameNotStarted(output), discardOld = true)

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }

  def receiveGameNotStarted(output: ActorRef): Receive = {
    case ConnectionHandler.StartGameIn =>
      val snakeState = context.actorOf(Snake.props())
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

  sequentialOperationsManager ! SequentialOperationsManager.NewSnake
  gameCycle ! GameCycle.ManageMe
}
