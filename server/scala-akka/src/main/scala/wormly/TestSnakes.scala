package wormly

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

object TestSnakes {
  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props =
    Props(new TestSnakes(gameCycle, sequentialOperationsManager))
}

class TestSnakes(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {
  private val config = context.system.settings.config
  private val testSnakesAmount = config.getInt("application.test.snakes-amount")

  override def receive: Receive = receiveWithTestSnakes(initTestSnakes())

  def initTestSnakes(): Set[ActorRef] = (0 until testSnakesAmount).map(_ => createTestSnake()).toSet

  def createTestSnake(): ActorRef = {
    val snake = context.actorOf(DummySnake.props(gameCycle, sequentialOperationsManager), Utils.actorName(DummySnake.getClass))
    context.watch(snake)
    snake
  }

  def receiveWithTestSnakes(testSnakes: Set[ActorRef]): Receive = {
    case Terminated(target) =>
      context.unwatch(target)
      val snake = createTestSnake()
      context.become(receiveWithTestSnakes((testSnakes - target) + snake), discardOld = true)

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}

object DummySnake {
  case object TurnRandomly

  def props(gameCycle: ActorRef, sequentialOperationsManager: ActorRef): Props = Props(new DummySnake(gameCycle, sequentialOperationsManager))
}

class DummySnake(gameCycle: ActorRef, sequentialOperationsManager: ActorRef) extends Actor with ActorLogging {
  import DummySnake._

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

  override def receive: Receive = {
    case ConnectionHandler.CollisionOut() =>
      gameClient ! PoisonPill
      self ! PoisonPill

    case TurnRandomly =>
      gameClient ! ConnectionHandler.CursorPositionIn(Random.nextInt(360))

    case ConnectionHandler.VisibleObjectsOut(_, food, _, _, y, x) =>
      if (0 > y || y > fieldHeight + 50 ||
        0 > x || x > fieldWidth + 50) {
        sender() ! PoisonPill
      } else if (food.nonEmpty) {
        val nearest = findNearestFood(food)
        val angle = Math.atan2(250 - nearest.y, 250 - nearest.x)
        sender() ! ConnectionHandler.CursorPositionIn(angle)
      }

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}
