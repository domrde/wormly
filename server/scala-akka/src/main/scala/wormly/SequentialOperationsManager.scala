package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import wormly.Worm.{WormPart, WormState}

import scala.concurrent.ExecutionContextExecutor
import scala.util.Random
import scala.concurrent.duration._
import scala.language.postfixOps

object SequentialOperationsManager {

  case object NewWorm

  case class Food(y: Double, x: Double, color: Color, value: Double, d: Double)

  case object Collision

  case class Feeding(foodValue: Double)

  case class SequentiallyProcessedObjects(worms: Map[ActorRef, WormState], food: Set[Food])

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

  def areCollide(partA: WormPart, sizeA: Double, partB: WormPart, sizeB: Double): Boolean = {
    if (collisionEnabled) {
    areCollide(partA.x, partA.y, sizeA / 2.0, partB.x, partB.y, sizeB / 2.0)
    } else {
      false
    }
  }

  def areCollide(partA: WormPart, sizeA: Double, food: Food): Boolean = {
    if (collisionEnabled) {
      areCollide(partA.x, partA.y, sizeA / 2.0, food.x, food.y, food.d / 2.0)
    } else {
      false
    }
  }

  def areCollide(ax: Double, ay: Double, ar: Double, bx: Double, by: Double, br: Double): Boolean = {
    Math.hypot(ax - bx, ay - by) <= (ar + br)
  }

  def findCollidingWorms(worms: Map[ActorRef, WormState]): Map[ActorRef, WormState] = {
    worms.filter { case (actorA, wormA) =>
      worms.exists { case (actorB, wormB) =>
        actorA != actorB && wormB.parts.tail.exists(part => areCollide(wormA.parts.head, wormA.size, part, wormB.size))
      }
    }
  }

  def findEatenFood(worms: Map[ActorRef, WormState], food: Set[Food]): Map[ActorRef, Food] = {
    worms.flatMap { case (actor, wormA) =>
      food.filter { food => areCollide(wormA.parts.head, wormA.size, food) }.map(eatenFood => (actor, eatenFood))
    }
  }

  def requestFoodGenerationFromDeadWorms(collidingWorms: Map[ActorRef, WormState]): Unit =
    collidingWorms.foreach { case (_, worm) =>
      context.actorOf(FoodFromDeadWormGenerator.props(worm))
    }

  def generateRandomFood(wormsAmount: Int): Set[Food] = {
    (1 to wormsAmount).map { _ =>
      val value = Random.nextDouble()
      val diam = value * foodValueToDiameterCoefficient
      Food(Random.nextInt(fieldHeight), Random.nextInt(fieldWidth), Utils.randomColor(), value, diam)
    }.toSet
  }

  override def receive: Receive = waitingForStateUpdate(Map.empty, generateRandomFood(initialAmountOfFood), Set.empty)

  def waitingForStateUpdate(worms: Map[ActorRef, WormState], foodSet: Set[Food], waitingList: Set[ActorRef]): Receive = {
    case NewWorm =>
      log.debug(s"New game client created ${sender()}. Worms amount: ${worms.size}, food amount: ${foodSet.size}")
      context.watch(sender())
      context.become(waitingForStateUpdate(worms, foodSet, waitingList + sender()), discardOld = true)

    case Terminated(target) =>
      log.debug(s"Game client terminated $target. Worms amount: ${worms.size}, food amount: ${foodSet.size}")
      context.unwatch(target)
      context.become(waitingForStateUpdate(worms - target, foodSet, waitingList - target), discardOld = true)

    case s @ Worm.WormState(_, _, _) =>
      val newWaitingList = waitingList - sender()
      if (newWaitingList.isEmpty) {
        val collidingWorms = findCollidingWorms(worms)
        collidingWorms.keys.foreach(_ ! Collision)
        val newWorms = (worms + (sender() -> s)) -- collidingWorms.keys

        val eatenFood = findEatenFood(worms, foodSet)
        eatenFood.foreach { case (actor, food) => actor ! Feeding(food.value) }
        val newFood = foodSet -- eatenFood.values
        requestFoodGenerationFromDeadWorms(collidingWorms)

        context.become(waitingForStateUpdate(newWorms, newFood, newWorms.keySet), discardOld = true)
        newWorms.keys.foreach(_ ! SequentiallyProcessedObjects(newWorms, newFood))
      } else {
        context.become(waitingForStateUpdate(worms + (sender() -> s), foodSet, newWaitingList), discardOld = true)
      }

    case FoodFromDeadWormGenerator.GeneratedFood(food) =>
      context.become(waitingForStateUpdate(worms, foodSet ++ food, waitingList), discardOld = true)

    case PrintStatistics =>
      log.debug(s"Worms amount: ${worms.size}, food amount: ${foodSet.size}")

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}