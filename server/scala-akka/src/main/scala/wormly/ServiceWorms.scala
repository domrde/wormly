package wormly

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}

object ServiceWorms {
  sealed trait JobType
  case object FieldRangeJob extends JobType

  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props = Props(new ServiceWorms(gameCycle, sequentialOperationsManager))
}

class ServiceWorms(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {
  import ServiceWorms._

  override def receive: Receive = receiveWithWorms(Map(createServiceWorm(FieldRangeJob) -> FieldRangeJob))

  def createServiceWorm(wormType: JobType): ActorRef = {
    wormType match {
      case FieldRangeJob =>
        val worm = context.actorOf(FieldRangeWorm.props(gameCycle, sequentialOperationsManager), Utils.actorName(FieldRangeWorm.getClass))
        context.watch(worm)
        worm
    }
  }

  def receiveWithWorms(worms: Map[ActorRef, JobType]): Receive = {
    case Terminated(target) =>
      context.unwatch(target)
      val wormType = worms(target)
      val worm = createServiceWorm(wormType)
      val newWorms = (worms - target) + (worm -> wormType)
      context.become(receiveWithWorms(newWorms), discardOld = true)

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}

object FieldRangeWorm {
  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props = Props(new FieldRangeWorm(gameCycle, sequentialOperationsManager))
}

class FieldRangeWorm(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {
  private val config = context.system.settings.config
  private val fieldHeight = config.getInt("application.game-field.height")
  private val fieldWidth = config.getInt("application.game-field.width")
  private val maxPartDiameter = config.getDouble("application.worm.maximum-part-diameter")
  private val distanceBetweenParts = config.getDouble("application.worm.distance-between-parts")

  private val gameClient = context.actorOf(GameClient.props(gameCycle, sequentialOperationsManager), Utils.actorName(GameClient.getClass))

  gameClient ! GameClient.OutputActor(self)
  gameClient ! ConnectionHandler.CanvasSize(5, 5)
  gameClient ! ConnectionHandler.StartGameIn("unused")

  private val borderWormDiameter = maxPartDiameter + 5
  private val length = Math.ceil((fieldHeight + fieldWidth) * 2.1 / distanceBetweenParts / borderWormDiameter).toInt
  gameClient ! GameClient.MoveWormImmediately(0, 0, -90.toRadians, borderWormDiameter, length)

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
