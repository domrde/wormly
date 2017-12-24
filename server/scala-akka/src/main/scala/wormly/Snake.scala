package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, Props}

import scala.util.Random

object Snake {

  case class ChangeAngle(newAngle: Double) // radians
  case class SnakePart(y: Double, x: Double)
  case class IncreaseSize(foodValue: Double)

  case object Update
  case class SnakeState(snakeParts: List[SnakePart], size: Double, color: Color)

  def props(): Props = Props(new Snake())

  def randomCoordinate(min: Int, max: Int): Double = {
//    val delta = max - min
//    Random.nextDouble() % (delta * 0.8) + (delta * 0.2)
    Random.nextInt(max - min) + min
  }
}

class Snake() extends Actor with ActorLogging {

  import Snake._

  private val config = context.system.settings.config
  private val fieldHeight = config.getInt("application.game-field.height")
  private val fieldWidth = config.getInt("application.game-field.width")
  private val initialPartSize = config.getInt("application.snake.initial-part-diameter")
  private val maximumPartSize = config.getInt("application.snake.maximum-part-diameter")
  private val distanceBetweenParts = config.getDouble("application.snake.distance-between-parts")
  private val initialLength = config.getInt("application.snake.initial-length")

  private val headColor: Color = Utils.randomColor()

  private def initSnake(initialY: Double, initialX: Double, initialAngle: Double): List[SnakePart] = {
    val oppositeAngle = (initialAngle + 180) % 360
    SnakePart(initialY, initialX) :: growParts(initialY, initialX, oppositeAngle, initialPartSize, initialLength - 1)
  }

  def growParts(y: Double, x: Double, angle: Double, size: Double, amount: Int): List[SnakePart] = {
    val heightIncrement = -size * distanceBetweenParts * Math.sin(angle)
    val widthIncrement = -size * distanceBetweenParts * Math.cos(angle)
    (1 to amount).map { idx =>
      SnakePart(
        y + idx * heightIncrement,
        x + idx * widthIncrement
      )
    }.toList
  }

  def update(angle: Double, size: Double, snakeParts: List[SnakePart]): List[SnakePart] = {
    growParts(snakeParts.head.y, snakeParts.head.x, angle, size, 1).reverse ::: snakeParts.dropRight(1)
  }

  def receiveWithState(angle: Double, size: Double, snakeParts: List[SnakePart]): Receive = {
    case ChangeAngle(newAngle) =>
      log.debug("Changing direction to {}", Math.toDegrees(newAngle))
      context.become(receiveWithState(newAngle, size, snakeParts), discardOld = true)

    case IncreaseSize(foodValue) =>
      log.debug("Increasing size by {}", foodValue)
      //todo: grow snake not only larger but longer
      if (size < maximumPartSize) {
        context.become(receiveWithState(angle, size + foodValue / size, snakeParts), discardOld = true)
      }

    case Update =>
      log.debug("Updating snake state")
      val updatedState = update(angle, size, snakeParts)
      context.become(receiveWithState(angle, size, updatedState), discardOld = true)
      sender() ! SnakeState(updatedState, size, headColor)

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }

  override def receive: Receive = {
    val initialAngle = Random.nextInt(360)
    receiveWithState(
      initialAngle,
      initialPartSize,
      initSnake(randomCoordinate(0, fieldHeight), randomCoordinate(0, fieldWidth), initialAngle)
    )
  }

}
