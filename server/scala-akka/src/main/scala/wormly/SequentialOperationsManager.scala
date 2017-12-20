package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import wormly.Snake.{SnakePart, SnakeState}

object SequentialOperationsManager {

  case object NewSnake

  case class Food(y: Double, x: Double, color: Color, value: Double)
  case object Collision
  case class Feeding(foodValue: Double)

  case class VisibilityWindow(upperBound: Double, leftBound: Double, lowerBound: Double, rightBound: Double)
  case class VisibleParts(snakeParts: List[SnakePart], size: Double, color: Color)
  case class VisibleObjects(snakeParts: List[VisibleParts], food: List[Food])

  def props(): Props = Props(new SequentialOperationsManager())
}

class SequentialOperationsManager extends Actor with ActorLogging {

  import SequentialOperationsManager._

  private val config = context.system.settings.config
  private val foodValueToDiameterCoefficient = config.getDouble("application.food-value-to-diameter-coefficient")

  def areCollide(partA: SnakePart, sizeA: Double, partB: SnakePart, sizeB: Double): Boolean = {
    val radA = sizeA / 2.0
    val radB = sizeB / 2.0
    val radiusSum = Math.pow(partA.x - partB.x, 2.0) + Math.pow(partA.y - partB.y, 2.0)
    Math.pow(radA - radB, 2.0) <= radiusSum && radiusSum <= Math.pow(radA + radB, 2.0)
  }

  def areCollide(partA: SnakePart, sizeA: Double, food: Food): Boolean = {
    val foodRadius = food.value * foodValueToDiameterCoefficient / 2.0
    val radA = sizeA / 2.0
    val radiusSum = Math.pow(partA.x - food.x, 2.0) + Math.pow(partA.y - food.y, 2.0)
    Math.pow(radA - foodRadius, 2.0) <= radiusSum && radiusSum <= Math.pow(radA + foodRadius, 2.0)
  }

  def sendCollisionMessages(snakes: Map[ActorRef, SnakeState]): Unit = {
    snakes.filter { case (_, snakeA) =>
      snakes.values.exists { snakeB =>
        snakeB.snakeParts.tail.exists(part => areCollide(snakeA.snakeParts.head, snakeA.size, part, snakeB.size))
      }
    }.foreach { case (snake, _) => snake ! Collision }
    // todo: remove from drawing
  }

  def sendFeedingMessages(snakes: Map[ActorRef, SnakeState], food: List[Food]): Unit = {
    snakes.foreach { case (actor, snakeA) =>
      food.foreach { food =>
        if (areCollide(snakeA.snakeParts.head, snakeA.size, food)) {
          actor ! Feeding(food.value)
          // todo: send message to delete food
        }
      }
    }
  }

  def filterVisibleParts(window: VisibilityWindow, state: SnakeState): VisibleParts = {
    val snakeParts = state.snakeParts.filter { case SnakePart(y, x) =>
      window.upperBound < y + state.size && y - state.size < window.lowerBound &&
        window.leftBound < x + state.size && x - state.size < window.rightBound
    }
    VisibleParts(snakeParts, state.size, state.color)
  }

  def filterVisibleFood(window: VisibilityWindow, food: List[Food]): List[Food] = {
    food.filter { case Food(y, x, _, v) =>
      val foodRadius = v * foodValueToDiameterCoefficient / 2.0
      window.upperBound < y + foodRadius && y - foodRadius < window.lowerBound &&
        window.leftBound < x + foodRadius && x - foodRadius < window.rightBound
    }
  }

  // todo: get canvas size from client
  def calculateVisibilityWindowBasedOnSnakeSize(snake: SnakeState): VisibilityWindow = {
    val height = 600
    val width = 800
    VisibilityWindow(
      upperBound = snake.snakeParts.head.y - 20 * snake.size,
      leftBound = snake.snakeParts.head.y - 20 * snake.size,
      lowerBound = snake.snakeParts.head.y + 20 * width / height * snake.size,
      rightBound = snake.snakeParts.head.y - 20 * width / height * snake.size
    )
  }

  def sendVisibleObjects(snakes: Map[ActorRef, SnakeState], food: List[Food]): Unit = {
    snakes.foreach { case (actor, snake) =>
      val window = calculateVisibilityWindowBasedOnSnakeSize(snake)
      val visibleParts = snakes.map { case (_, otherSnake) => filterVisibleParts(window, otherSnake) }.toList
      val visibleFood = filterVisibleFood(window, food)
      actor ! VisibleObjects(visibleParts, visibleFood)
    }
  }

  override def receive: Receive = waitingForStateUpdate(Map.empty, List.empty, Set.empty)

  def waitingForStateUpdate(snakes: Map[ActorRef, SnakeState], food: List[Food], waitingList: Set[ActorRef]): Receive = {
    case NewSnake =>
      context.watch(sender())

    case Terminated(target) =>
      context.become(waitingForStateUpdate(snakes - target, food, waitingList - target), discardOld = true)

    case s @ Snake.SnakeState(_, _, _) =>
      val newWaitingList = waitingList - sender()
      if (newWaitingList.isEmpty) {
        context.become(waitingForStateUpdate(snakes + (sender() -> s), food, snakes.keySet), discardOld = true)
        sendCollisionMessages(snakes)
        sendFeedingMessages(snakes, food)
        sendVisibleObjects(snakes, food)
      } else {
        context.become(waitingForStateUpdate(snakes + (sender() -> s), food, newWaitingList), discardOld = true)
      }

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}
