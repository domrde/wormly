package wormly

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

object ServiceWorms {

  sealed trait JobType

  case object FieldRangeJob extends JobType

  case object AutomatedWorm extends JobType

  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props = Props(new ServiceWorms(gameCycle, sequentialOperationsManager))
}

class ServiceWorms(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {

  import ServiceWorms._

  private val config = context.system.settings.config
  private val testWormsAmount = config.getInt("application.test.worms-amount")

  override def receive: Receive = receiveWithWorms(
    (0 until testWormsAmount).map(_ => createServiceWorm(AutomatedWorm) -> AutomatedWorm).toMap +
      (createServiceWorm(FieldRangeJob) -> FieldRangeJob)
  )

  def createServiceWorm(wormType: JobType): ActorRef = {
    log.debug("Creating {} worm", wormType)
    wormType match {
      case FieldRangeJob =>
        val worm = context.actorOf(FieldRangeWorm.props(gameCycle, sequentialOperationsManager), Utils.actorName(FieldRangeWorm.getClass))
        context.watch(worm)
        worm

      case AutomatedWorm =>
        val worm = context.actorOf(DummyWorm.props(gameCycle, sequentialOperationsManager), Utils.actorName(DummyWorm.getClass))
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
  gameClient ! ConnectionHandler.CanvasSize(500, 500)
  gameClient ! ConnectionHandler.StartGameIn("unused")

  private val borderWormDiameter = maxPartDiameter + 5
  private val length = Math.ceil((fieldHeight + fieldWidth) * 2.1 / distanceBetweenParts / borderWormDiameter).toInt
  gameClient ! GameClient.MoveWormImmediately(0, 0, -90.toRadians, borderWormDiameter, length)

  override def receive: Receive = {
    case ConnectionHandler.CollisionOut() =>
      gameClient ! PoisonPill
      self ! PoisonPill

    case ConnectionHandler.VisibleObjectsOut(_, _, _, _, y, x) if Math.hypot(y - 0, x - 0) < 0.5 =>
      sender() ! ConnectionHandler.CursorPositionIn(-90.toRadians)

    case ConnectionHandler.VisibleObjectsOut(_, _, _, _, y, x) if Math.hypot(y - fieldHeight, x - 0) < 0.5 =>
      sender() ! ConnectionHandler.CursorPositionIn(180.toRadians)

    case ConnectionHandler.VisibleObjectsOut(_, _, _, _, y, x) if Math.hypot(y - fieldHeight, x - fieldWidth) < 0.5 =>
      sender() ! ConnectionHandler.CursorPositionIn(90.toRadians)

    case ConnectionHandler.VisibleObjectsOut(_, _, _, _, y, x) if Math.hypot(y - 0, x - fieldWidth) < 0.5 =>
      sender() ! ConnectionHandler.CursorPositionIn(0.toRadians)

    case ConnectionHandler.VisibleObjectsOut(_, _, _, _, _, _) =>

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }

}

object DummyWorm {

  case object TurnRandomly

  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props = Props(new DummyWorm(gameCycle, sequentialOperationsManager))
}

class DummyWorm(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {

  import DummyWorm._

  implicit val executionContext: ExecutionContextExecutor = context.system.dispatcher
  context.system.scheduler.schedule(0 millis, 2 second, self, TurnRandomly)

  private val config = context.system.settings.config
  private val fieldHeight = config.getInt("application.game-field.height")
  private val fieldWidth = config.getInt("application.game-field.width")

  private val gameClient = context.actorOf(GameClient.props(gameCycle, sequentialOperationsManager), Utils.actorName(GameClient.getClass))

  gameClient ! GameClient.OutputActor(self)
  gameClient ! ConnectionHandler.CanvasSize(500, 500)
  gameClient ! ConnectionHandler.StartGameIn("unused")

  def findNearestFood(food: Set[ConnectionHandler.FoodOut]): ConnectionHandler.FoodOut = {
    food.minBy(f => Math.hypot(f.x - 250, f.y - 250))
  }

  override def receive: Receive = receiveWithAngle(None)

  def receiveWithAngle(angle: Option[Double]): Receive = {
    case ConnectionHandler.CollisionOut() =>
      gameClient ! ConnectionHandler.StartGameIn("unused")

    case TurnRandomly =>
      val newAngle = Random.nextInt(360)
      gameClient ! ConnectionHandler.CursorPositionIn(newAngle.toRadians)
      context.become(receiveWithAngle(Some(newAngle)), discardOld = true)

    case ConnectionHandler.VisibleObjectsOut(_, food, _, _, y, x) =>
      val newAngleOpt: Option[Double] = {
        if (y < fieldHeight * 0.1) { // upper bound
          if (angle.isDefined) {
            Some(-angle.get)
          } else {
            Some(-90)
          }
        }
        else if (y > fieldHeight * 0.9) { //lower bound
          if (angle.isDefined) {
            Some(-angle.get)
          } else {
            Some(90)
          }
        }
        else if (x < fieldWidth * 0.1) { // left bound
          if (angle.isDefined) {
            Some(-angle.get)
          } else {
            Some(180)
          }
        }
        else if (x > fieldWidth * 0.9) { // right bound
          if (angle.isDefined) {
            Some(-angle.get)
          } else {
            Some(0)
          }
        }
        else if (food.nonEmpty) {
          val nearest = findNearestFood(food)
          Some(Math.atan2(250 - nearest.y, 250 - nearest.x).toDegrees)
        }
        else None
      }
      newAngleOpt foreach { newAngle =>
        gameClient ! ConnectionHandler.CursorPositionIn(newAngle.toRadians)
        context.become(receiveWithAngle(Some(newAngle)), discardOld = true)
      }

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}
