package wormly

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}

object GameClient {

  case class OutputActor(outputActor: ActorRef)

  // For service worms
  case class MoveWormImmediately(y: Double, x: Double, angle: Double, size: Double, parts: Int)

  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props =
    Props(new GameClient(gameCycle, sequentialOperationsManager))

}

class GameClient(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {
  import GameClient._

  private val outputDataMapper = context.actorOf(OutputDataMapper.props(), Utils.actorName(OutputDataMapper.getClass))

  override def receive: Receive = receiveWithNoOutput

  def wormDying(worm: ActorRef, output: ActorRef, canvasSize: ConnectionHandler.CanvasSize): Receive = {
    case Terminated(target) if target == worm =>
      log.debug("Terminated worm {} in game {}", target, self)
      output ! ConnectionHandler.CollisionOut()
      gameCycle ! GameCycle.UnmanageMe
      context.unwatch(target)
      context.become(receiveGameNotStarted(output, canvasSize), discardOld = true)

    case other =>
  }

  def receiveGameStarted(worm: ActorRef, output: ActorRef, canvasSize: ConnectionHandler.CanvasSize): Receive = {
    case GameCycle.Tick =>
      worm ! Worm.Update


    // --------- Sequential search of collisions with worms and food ----------
    case s @ Worm.WormState(_, _, _) =>
      sequentialOperationsManager ! s

    case SequentialOperationsManager.Collision =>
      worm ! PoisonPill
      context.become(wormDying(worm, output, canvasSize))

    case SequentialOperationsManager.Feeding(value) =>
      worm ! Worm.IncreaseSize(value)


    // --------- Prepared output data filtering and mapping -------------------
    case SequentialOperationsManager.SequentiallyProcessedObjects(parts, food) =>
      outputDataMapper ! OutputDataMapper.FilterVisibleObjects(parts, food, canvasSize)

    case vo @ ConnectionHandler.VisibleObjectsOut(_, _, _, _, _, _) =>
      output ! vo


    // --------- User input ---------------------------------------------------
    case ConnectionHandler.CursorPositionIn(angle) =>
      worm ! Worm.ChangeAngle(angle)

    case c @ ConnectionHandler.CanvasSize(_, _) =>
      context.become(receiveGameStarted(worm, output, c), discardOld = true)

    case m @ MoveWormImmediately(_, _, _, _, _) =>
      worm ! m


    // --------- Created actors control ---------------------------------------
    case Terminated(target) if target == worm =>
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
      val worm = context.actorOf(Worm.props(), Utils.actorName(Worm.getClass))
      log.debug("Starting game {} with worm {}", self, worm)
      gameCycle ! GameCycle.ManageMe
      sequentialOperationsManager ! SequentialOperationsManager.NewWorm
      context.watch(worm)
      context.become(receiveGameStarted(worm, output, canvasSize), discardOld = true)

    case SequentialOperationsManager.SequentiallyProcessedObjects(_, _) =>
      // todo: prevent sending of this message

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
