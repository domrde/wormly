package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, Props}
import wormly.SequentialOperationsManager.Food
import wormly.Snake.SnakeState

import scala.util.Random

object FoodFromDeadSnakeGenerator {
  case class GeneratedFood(food: Set[Food])


  def props(deadSnake: SnakeState): Props = Props(new FoodFromDeadSnakeGenerator(deadSnake))
}

class FoodFromDeadSnakeGenerator(deadSnake: SnakeState) extends Actor with ActorLogging {
  import FoodFromDeadSnakeGenerator._

  private val config = context.system.settings.config
  private val foodValueToDiameterCoefficient = config.getDouble("application.food.value-to-diameter-coefficient")
  private val maxFoodValue = config.getDouble("application.food.max-value")

  override def receive: Receive = {
    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }

  def generateFoodForSnakePart(deadSnakePart: Snake.SnakePart, size: Double, color: Color): Set[Food] = {
    val lowerBound = deadSnakePart.y - size / 2.0
    val leftBound = deadSnakePart.x - size / 2.0

    val value = 0.1 + (Math.min(size / 2.0, maxFoodValue) - 0.1) * Random.nextDouble()
    val diam = value * foodValueToDiameterCoefficient
    Set(Food(lowerBound + Random.nextInt(size.toInt), leftBound + Random.nextInt(size.toInt), color, value, diam))
  }

  context.parent ! GeneratedFood(
    deadSnake.snakeParts.flatMap(part =>
      generateFoodForSnakePart(part, deadSnake.size, deadSnake.color)
    ).toSet
  )
}
