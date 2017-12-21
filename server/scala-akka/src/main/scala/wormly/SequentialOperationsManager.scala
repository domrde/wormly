package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import wormly.Snake.{SnakePart, SnakeState}

import scala.util.Random

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
  private val fieldHeight = config.getInt("application.game-field-height")
  private val fieldWidth = config.getInt("application.game-field-width")

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

  def findCollidingSnakes(snakes: Map[ActorRef, SnakeState]): Map[ActorRef, SnakeState] = {
    snakes.filter { case (_, snakeA) =>
      snakes.values.exists { snakeB =>
        snakeB.snakeParts.tail.exists(part => areCollide(snakeA.snakeParts.head, snakeA.size, part, snakeB.size))
      }
    }
  }

  def findEatenFood(snakes: Map[ActorRef, SnakeState], food: List[Food]): Map[ActorRef, Food] = {
    snakes.flatMap { case (actor, snakeA) =>
      food.filter { food => areCollide(snakeA.snakeParts.head, snakeA.size, food) }.map(eatenFood => (actor, eatenFood))
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

  def generateFoodForSnakePart(deadSnakePart: Snake.SnakePart, size: Double, color: Color): List[Food] = {
    val lowerBound = deadSnakePart.y - size / 2.0
    val leftBound = deadSnakePart.x - size / 2.0
    (1 until size.toInt).map { _ =>
      Food(lowerBound + Random.nextInt(size.toInt), leftBound + Random.nextInt(size.toInt), color, Random.nextDouble())
    }.toList
  }

  def generateFood(collidingSnakes: Map[ActorRef, SnakeState], amountToGenerate: Int): List[Food] = {
    collidingSnakes.flatMap { case (_, snake) =>
      snake.snakeParts.flatMap { part =>
        generateFoodForSnakePart(part, snake.size, snake.color)
      }
    } ++
    (1 to amountToGenerate).map { _ =>
      Food(Random.nextInt(fieldHeight), Random.nextInt(fieldWidth), Utils.randomColor(), Random.nextDouble())
    }
  }.toList

  override def receive: Receive = waitingForStateUpdate(Map.empty, generateFood(Map.empty, 100), Set.empty)

  def waitingForStateUpdate(snakes: Map[ActorRef, SnakeState], foodList: List[Food], waitingList: Set[ActorRef]): Receive = {
    case NewSnake =>
      context.watch(sender())

    case Terminated(target) =>
      context.become(waitingForStateUpdate(snakes - target, foodList, waitingList - target), discardOld = true)

    case s @ Snake.SnakeState(_, _, _) =>
      val newWaitingList = waitingList - sender()
      if (newWaitingList.isEmpty) {
        val collidingSnakes = findCollidingSnakes(snakes)
        collidingSnakes.keys.foreach(_ ! Collision)

        val eatenFood = findEatenFood(snakes, foodList)
        eatenFood.foreach { case (actor, food) => actor ! Feeding(food.value)}

        val newSnakes = (snakes + (sender() -> s)) -- collidingSnakes.keys
        val newFood = generateFood(collidingSnakes, eatenFood.size)

        context.become(waitingForStateUpdate(newSnakes, newFood, newSnakes.keySet), discardOld = true)
        sendVisibleObjects(snakes, foodList)
      } else {
        context.become(waitingForStateUpdate(snakes + (sender() -> s), foodList, newWaitingList), discardOld = true)
      }

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}
