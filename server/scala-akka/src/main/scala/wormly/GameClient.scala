package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

import scala.util.Random

object GameClient {

  case class OutputActor(outputActor: ActorRef)

  case class ChangeAngle(newAngle: Double)

  case class SnakePart(y: Double, x: Double)

  case class IncreaseSize(foodValue: Double)

  case class Update()

  case class GetVisibleParts(upperBound: Double, leftBound: Double, lowerBound: Double, rightBound: Double)

  case class VisibleParts(snakeParts: List[SnakePart], size: Double, color: Color)

  case class ReinitSnake(initialY: Double, initialX: Double, initialAngle: Double)

  def props(): Props = Props(new GameClient())

  def randomCoordinate(min: Int, max: Int): Double = {
    val delta = max - min
    Random.nextInt(delta / 2) + (delta / 4)
  }
}

class GameClient() extends Actor with ActorLogging {

  import GameClient._

  private val config = context.system.settings.config
  private val fieldHeight = config.getInt("application.game-field-height")
  private val fieldWidth = config.getInt("application.game-field-width")
  private val initialPartSize = config.getInt("application.initial-snake-part-size")
  private val maximumPartSize = config.getInt("application.maximum-snake-part-size")
  private val initialLength = config.getInt("application.initial-snake-length")
  private val speed = config.getInt("application.snake-speed")

  private val headColor: Color = new Color(Random.nextInt(255), Random.nextInt(255), Random.nextInt(255))

  private def initSnake(initialY: Double, initialX: Double, initialAngle: Double): List[SnakePart] = {
    val oppositeAngle = (initialAngle + 180) % 360
    SnakePart(initialY, initialX) :: growParts(initialY, initialX, oppositeAngle, initialPartSize, initialLength - 1)
  }

  def growParts(y: Double, x: Double, angle: Double, size: Double, amount: Int): List[SnakePart] = {
    val heightIncrement = size / 2.0 * Math.cos(Math.toRadians(angle))
    val widthIncrement = size / 2.0 * Math.sin(Math.toRadians(angle))
    (1 to amount).map { idx =>
      SnakePart(
        y + idx * heightIncrement,
        x + idx * widthIncrement
      )
    }.toList
  }

  def update(angle: Double, size: Double, snakeParts: List[SnakePart]): List[SnakePart] = {
    growParts(snakeParts.head.y, snakeParts.head.x, angle, size, speed).reverse ::: snakeParts.dropRight(speed)
  }

  def filterVisibleParts(upperBound: Double, leftBound: Double, lowerBound: Double, rightBound: Double,
                         size: Double, snakeParts: List[SnakePart]): List[SnakePart] = {
    snakeParts.filter { case SnakePart(y, x) =>
      upperBound < y + size && y - size < lowerBound &&
        leftBound < x + size && x - size < rightBound
    }
  }

  def receiveWithState(angle: Double, size: Double, snakeParts: List[SnakePart]): Receive = {
    case ReinitSnake(y: Double, x: Double, a: Double) =>
      context.become(receiveWithState(a, initialPartSize, initSnake(y, x, a)))

    case ChangeAngle(newAngle) =>
      context.become(receiveWithState(newAngle, size, snakeParts), discardOld = true)

    case IncreaseSize(foodValue) =>
      if (size < maximumPartSize) {
        context.become(receiveWithState(angle, size * foodValue, snakeParts), discardOld = true)
      }

    case Update() =>
      context.become(receiveWithState(angle, size, update(angle, size, snakeParts)), discardOld = true)

    case GetVisibleParts(upperBound, leftBound, lowerBound, rightBound) =>
      sender() ! VisibleParts(filterVisibleParts(upperBound, leftBound, lowerBound, rightBound, size, snakeParts), size, headColor)

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }

  override def receive: Receive = {
    val initialAngle = Random.nextInt(360)
    receiveWithState(initialAngle, initialPartSize, initSnake(randomCoordinate(0, fieldWidth), randomCoordinate(0, fieldWidth), initialAngle))
  }

}
