package wormly

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}

object GameClient {

  case class OutputActor(outputActor: ActorRef)

  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props =
    Props(new GameClient(gameCycle, sequentialOperationsManager))

}

class GameClient(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {
  import GameClient._

  private val outputDataMapper = context.actorOf(OutputDataMapper.props(), Utils.actorName(OutputDataMapper.getClass))

  override def receive: Receive = receiveWithNoOutput

  def snakeDying(snakeState: ActorRef, output: ActorRef, canvasSize: ConnectionHandler.CanvasSize): Receive = {
    case Terminated(target) if target == snakeState =>
      log.debug("Terminated: {}", target)
      output ! ConnectionHandler.CollisionOut()
      gameCycle ! GameCycle.UnmanageMe
      context.unwatch(target)
      context.become(receiveGameNotStarted(output, canvasSize), discardOld = true)

    case other =>
  }

  def receiveGameStarted(snakeState: ActorRef, output: ActorRef, canvasSize: ConnectionHandler.CanvasSize): Receive = {
    case GameCycle.Tick =>
      log.debug("Tick. Snake update")
      snakeState ! Snake.Update


    // --------- Sequential search of collisions with snakes and food ---------
    case s @ Snake.SnakeState(_, _, _) =>
      log.debug("Snake state updated: {}", s)
      sequentialOperationsManager ! s

    case SequentialOperationsManager.Collision =>
      log.debug("Collision")
      snakeState ! PoisonPill
      context.become(snakeDying(snakeState, output, canvasSize))

    case SequentialOperationsManager.Feeding(value) =>
      log.debug("Feeding for {}", value)
      snakeState ! Snake.IncreaseSize(value)


    // --------- Prepared output data filtering and mapping -------------------
    case SequentialOperationsManager.SequentiallyProcessedObjects(parts, food) =>
      log.debug("Sequentially processed objects {}, {}", parts, food)
      outputDataMapper ! OutputDataMapper.FilterVisibleObjects(parts, food, canvasSize)

    case vo @ ConnectionHandler.VisibleObjectsOut(_, _, _, _) =>
      log.debug("Visible objects: {}", vo)
      output ! vo


    // --------- User input ---------------------------------------------------
    case ConnectionHandler.CursorPositionIn(angle) =>
      log.debug("CursorPositionIn: {}", angle)
      snakeState ! Snake.ChangeAngle(angle)

    case c @ ConnectionHandler.CanvasSize(_, _) =>
      context.become(receiveGameStarted(snakeState, output, c), discardOld = true)


    // --------- Created actors control ---------------------------------------
    case Terminated(target) if target == snakeState =>
      log.debug("Terminated: {}", target)
      output ! ConnectionHandler.CollisionOut()
      gameCycle ! GameCycle.UnmanageMe
      context.unwatch(target)
      context.become(receiveGameNotStarted(output, canvasSize), discardOld = true)

    case other =>
      log.error("Unexpected message {} from {}. receiveGameStarted", other, sender())
  }

  def receiveGameNotStarted(output: ActorRef, canvasSize: ConnectionHandler.CanvasSize): Receive = {
    case ConnectionHandler.StartGameIn(_) =>
      log.debug("Starting game")
      val snakeState = context.actorOf(Snake.props(), Utils.actorName(Snake.getClass))
      gameCycle ! GameCycle.ManageMe
      sequentialOperationsManager ! SequentialOperationsManager.NewSnake
      context.watch(snakeState)
      context.become(receiveGameStarted(snakeState, output, canvasSize), discardOld = true)

    case ConnectionHandler.CursorPositionIn(_) =>

    case c @ ConnectionHandler.CanvasSize(_, _) =>
      context.become(receiveGameNotStarted(output, c), discardOld = true)

    case other =>
      log.error("Unexpected message {} from {}. receiveGameNotStarted", other, sender())
  }

  def receiveNoCanvasSize(output: ActorRef): Receive = {
    case c @ ConnectionHandler.CanvasSize(_, _) =>
      context.become(receiveGameNotStarted(output, c), discardOld = true)

    case other =>
      log.error("Unexpected message {} from {}. receiveNoCanvasSize", other, sender())
  }

  val receiveWithNoOutput: Receive = {
    case OutputActor(outputActor) =>
      context.become(receiveNoCanvasSize(outputActor), discardOld = true)

    case other =>
      log.error("Unexpected message {} from {}. receiveWithNoOutput", other, sender())
  }
}
