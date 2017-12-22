package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import wormly.Snake.{SnakePart, SnakeState}

import scala.util.Random

object SequentialOperationsManager {

  case object NewSnake

  case class Food(y: Double, x: Double, color: Color, value: Double, d: Double)

  case object Collision

  case class Feeding(foodValue: Double)

  case class SequentiallyProcessedObjects(snakes: Map[ActorRef, SnakeState], foodList: List[Food])

  def props(): Props = Props(new SequentialOperationsManager())
}

class SequentialOperationsManager extends Actor with ActorLogging {

  import SequentialOperationsManager._

  private val config = context.system.settings.config
  private val foodValueToDiameterCoefficient = config.getDouble("application.food.value-to-diameter-coefficient")
  private val initialAmountOfFood = config.getInt("application.food.initial-amount")
  private val fieldHeight = config.getInt("application.game-field.height")
  private val fieldWidth = config.getInt("application.game-field.width")
  private val collisionEnabled = config.getBoolean("application.test.collision")

  def areCollide(partA: SnakePart, sizeA: Double, partB: SnakePart, sizeB: Double): Boolean = {
    areCollide(partA.x, partA.y, sizeA / 2.0, partB.x, partB.y, sizeB / 2.0)
  }

  def areCollide(partA: SnakePart, sizeA: Double, food: Food): Boolean = {
    if (collisionEnabled) {
      areCollide(partA.x, partA.y, sizeA / 2.0, food.x, food.y, food.d / 2.0)
    } else {
      false
    }
  }

  def areCollide(ax: Double, ay: Double, ar: Double, bx: Double, by: Double, br: Double): Boolean = {
    Math.hypot(ax - bx, ay - by) <= (ar + br)
  }

  def findCollidingSnakes(snakes: Map[ActorRef, SnakeState]): Map[ActorRef, SnakeState] = {
    snakes.filter { case (actorA, snakeA) =>
      snakes.exists { case (actorB, snakeB) =>
        actorA != actorB && snakeB.snakeParts.tail.exists(part => areCollide(snakeA.snakeParts.head, snakeA.size, part, snakeB.size))
      }
    }
  }

  def findEatenFood(snakes: Map[ActorRef, SnakeState], food: List[Food]): Map[ActorRef, Food] = {
    snakes.flatMap { case (actor, snakeA) =>
      food.filter { food => areCollide(snakeA.snakeParts.head, snakeA.size, food) }.map(eatenFood => (actor, eatenFood))
    }
  }

  def generateFoodForSnakePart(deadSnakePart: Snake.SnakePart, size: Double, color: Color): List[Food] = {
    val lowerBound = deadSnakePart.y - size / 2.0
    val leftBound = deadSnakePart.x - size / 2.0
    (1 until size.toInt).map { _ =>
      val value = Random.nextDouble()
      val diam = value * foodValueToDiameterCoefficient
      Food(lowerBound + Random.nextInt(size.toInt), leftBound + Random.nextInt(size.toInt), color, value, diam)
    }.toList
  }

  def generateFood(collidingSnakes: Map[ActorRef, SnakeState], amountToGenerate: Int): List[Food] = {
    collidingSnakes.flatMap { case (_, snake) =>
      snake.snakeParts.flatMap { part =>
        generateFoodForSnakePart(part, snake.size, snake.color)
      }
    } ++
      (1 to amountToGenerate).map { _ =>
        val value = Random.nextDouble()
        val diam = value * foodValueToDiameterCoefficient
        Food(Random.nextInt(fieldHeight), Random.nextInt(fieldWidth), Utils.randomColor(), value, diam)
      }
  }.toList

  override def receive: Receive = {
    waitingForStateUpdate(Map.empty, generateFood(Map.empty, initialAmountOfFood), Set.empty)
  }

  def waitingForStateUpdate(snakes: Map[ActorRef, SnakeState], foodList: List[Food], waitingList: Set[ActorRef]): Receive = {
    case NewSnake =>
      log.debug("New snake created {}", sender())
      context.watch(sender())

    case Terminated(target) =>
      log.debug("Snake terminated {}", target)
      context.unwatch(target)
      context.become(waitingForStateUpdate(snakes - target, foodList, waitingList - target), discardOld = true)

    case s@Snake.SnakeState(_, _, _) =>
      val newWaitingList = waitingList - sender()
      if (newWaitingList.isEmpty) {
        log.debug("Snake state updated. Calculating client data")
        val collidingSnakes = findCollidingSnakes(snakes)
        collidingSnakes.keys.foreach(_ ! Collision)

        val eatenFood = findEatenFood(snakes, foodList)
        eatenFood.foreach { case (actor, food) => actor ! Feeding(food.value) }

        val newSnakes = (snakes + (sender() -> s)) -- collidingSnakes.keys
        val newFood = generateFood(collidingSnakes, eatenFood.size)

        context.become(waitingForStateUpdate(newSnakes, newFood, newSnakes.keySet), discardOld = true)
        newSnakes.keys.foreach(_ ! SequentiallyProcessedObjects(newSnakes, newFood))
      } else {
        log.debug("Snake state updated. Waiting for clients {}", waitingList)
        context.become(waitingForStateUpdate(snakes + (sender() -> s), foodList, newWaitingList), discardOld = true)
      }

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}
