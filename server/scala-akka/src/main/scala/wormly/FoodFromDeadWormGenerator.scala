package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, Props}
import wormly.SequentialOperationsManager.Food
import wormly.Worm.WormState

import scala.util.Random

object FoodFromDeadWormGenerator {
  case class GeneratedFood(food: Set[Food])


  def props(deadWorm: WormState): Props = Props(new FoodFromDeadWormGenerator(deadWorm))
}

class FoodFromDeadWormGenerator(deadWorm: WormState) extends Actor with ActorLogging {
  import FoodFromDeadWormGenerator._

  private val config = context.system.settings.config
  private val foodValueToDiameterCoefficient = config.getDouble("application.food.value-to-diameter-coefficient")
  private val maxFoodValue = config.getDouble("application.food.max-value")

  override def receive: Receive = {
    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }

  def generateFoodForWormPart(deadWormPart: Worm.WormPart, size: Double, color: Color): Set[Food] = {
    val lowerBound = deadWormPart.y - size / 2.0
    val leftBound = deadWormPart.x - size / 2.0

    val maxPossibleFoodValueForPart = Math.min(size / foodValueToDiameterCoefficient, maxFoodValue)
    val value = 0.01 + (maxPossibleFoodValueForPart - 0.01) * Random.nextDouble()
    val diam = value * foodValueToDiameterCoefficient
    Set(Food(lowerBound + Random.nextInt(size.toInt), leftBound + Random.nextInt(size.toInt), color, value, diam))
  }

  context.parent ! GeneratedFood(
    deadWorm.parts.flatMap(part =>
      generateFoodForWormPart(part, deadWorm.size, deadWorm.color)
    ).toSet
  )
}
