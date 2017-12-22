package wormly

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

object TestSnakes {

  case object TurnRandomSnakes

  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props =
    Props(new TestSnakes(gameCycle, sequentialOperationsManager))
}

class TestSnakes(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {
  import TestSnakes._

  private val config = context.system.settings.config
  private val testSnakesAmount = config.getInt("application.test.snakes-amount")
  private val fieldHeight = config.getInt("application.game-field.height")
  private val fieldWidth = config.getInt("application.game-field.width")

  override def receive: Receive = receiveWithTestSnakes(initTestSnakes())

  def initTestSnakes(): Set[ActorRef] = (0 until testSnakesAmount).map(_ => createTestSnake()).toSet

  def createTestSnake(): ActorRef = {
    val snake = context.actorOf(GameClient.props(gameCycle, sequentialOperationsManager), Utils.actorName(GameClient.getClass))
    snake ! GameClient.OutputActor(self)
    snake ! ConnectionHandler.CanvasSize(500, 500)
    snake ! ConnectionHandler.StartGameIn("unused")
    context.watch(snake)
    snake
  }

  implicit val executionContext: ExecutionContextExecutor = context.system.dispatcher
  context.system.scheduler.schedule(0 millis, 100 millis, self, TurnRandomSnakes)

  def receiveWithTestSnakes(testSnakes: Set[ActorRef]): Receive = {
    case Terminated(target) =>
      context.unwatch(target)
      val snake = createTestSnake()
      context.watch(snake)
      context.become(receiveWithTestSnakes((testSnakes - target) + snake), discardOld = true)

    case ConnectionHandler.CollisionOut() =>
      sender() ! PoisonPill

    case ConnectionHandler.VisibleObjectsOut(_, _, y, x) =>
      if (0 > y || y > fieldHeight ||
        0 > x || x > fieldWidth) {
        sender() ! PoisonPill
      }

    case TurnRandomSnakes =>
      if (testSnakes.nonEmpty) {
        (0 to Random.nextInt(testSnakes.size / 2)) foreach { _ =>
          testSnakes.iterator.drop(Random.nextInt(testSnakes.size)).next ! ConnectionHandler.CursorPositionIn(Math.toRadians(Random.nextInt(360) - 180))
        }
      }

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}
