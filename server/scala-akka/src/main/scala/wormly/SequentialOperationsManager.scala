package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import wormly.Snake.{SnakePart, SnakeState}

import scala.concurrent.ExecutionContextExecutor
import scala.util.Random
import scala.concurrent.duration._
import scala.language.postfixOps

object SequentialOperationsManager {

  case object NewSnake

  case class Food(y: Double, x: Double, color: Color, value: Double, d: Double)

  case object Collision

  case class Feeding(foodValue: Double)

  case class SequentiallyProcessedObjects(snakes: Map[ActorRef, SnakeState], food: Set[Food])

  case object PrintStatistics

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

  implicit val executionContext: ExecutionContextExecutor = context.system.dispatcher
  context.system.scheduler.schedule(0 millis, 500 millis, self, PrintStatistics)

  def areCollide(partA: SnakePart, sizeA: Double, partB: SnakePart, sizeB: Double): Boolean = {
    if (collisionEnabled) {
    areCollide(partA.x, partA.y, sizeA / 2.0, partB.x, partB.y, sizeB / 2.0)
    } else {
      false
    }
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

  def findEatenFood(snakes: Map[ActorRef, SnakeState], food: Set[Food]): Map[ActorRef, Food] = {
    snakes.flatMap { case (actor, snakeA) =>
      food.filter { food => areCollide(snakeA.snakeParts.head, snakeA.size, food) }.map(eatenFood => (actor, eatenFood))
    }
  }

  def requestFoodGenerationFromDeadSnakes(collidingSnakes: Map[ActorRef, SnakeState]): Unit =
    collidingSnakes.foreach { case (_, snake) =>
      context.actorOf(FoodFromDeadSnakeGenerator.props(snake))
    }

  def generateRandomFood(snakesAmount: Int): Set[Food] = {
    (1 to snakesAmount).map { _ =>
      val value = Random.nextDouble()
      val diam = value * foodValueToDiameterCoefficient
      Food(Random.nextInt(fieldHeight), Random.nextInt(fieldWidth), Utils.randomColor(), value, diam)
    }.toSet
  }

  override def receive: Receive = waitingForStateUpdate(Map.empty, generateRandomFood(initialAmountOfFood), Set.empty)

  def waitingForStateUpdate(snakes: Map[ActorRef, SnakeState], foodSet: Set[Food], waitingList: Set[ActorRef]): Receive = {
    case NewSnake =>
      log.debug("New snake created {}", sender())
      context.watch(sender())

    case Terminated(target) =>
      log.debug("Snake terminated {}", target)
      context.unwatch(target)
      context.become(waitingForStateUpdate(snakes - target, foodSet, waitingList - target), discardOld = true)

    case s @ Snake.SnakeState(_, _, _) =>
      val newWaitingList = waitingList - sender()
      if (newWaitingList.isEmpty) {
        log.debug("Snake state updated. Calculating client data")
        val collidingSnakes = findCollidingSnakes(snakes)
        collidingSnakes.keys.foreach(_ ! Collision)
        val newSnakes = (snakes + (sender() -> s)) -- collidingSnakes.keys

        val eatenFood = findEatenFood(snakes, foodSet)
        eatenFood.foreach { case (actor, food) => actor ! Feeding(food.value) }
        val newFood = foodSet -- eatenFood.values
        requestFoodGenerationFromDeadSnakes(collidingSnakes)

        context.become(waitingForStateUpdate(newSnakes, newFood, newSnakes.keySet), discardOld = true)
        newSnakes.keys.foreach(_ ! SequentiallyProcessedObjects(newSnakes, newFood))
      } else {
        log.debug("Snake state updated. Waiting for clients {}", waitingList)
        context.become(waitingForStateUpdate(snakes + (sender() -> s), foodSet, newWaitingList), discardOld = true)
      }

    case FoodFromDeadSnakeGenerator.GeneratedFood(food) =>
      context.become(waitingForStateUpdate(snakes, foodSet ++ food, waitingList), discardOld = true)

    case PrintStatistics =>
      log.info(s"Snakes amount: ${snakes.size}, food amount: ${foodSet.size}")

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}