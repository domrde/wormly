package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, Props}

import scala.util.Random

object Snake {

  case class ChangeAngle(newAngle: Double)
  case class SnakePart(y: Double, x: Double)
  case class IncreaseSize(foodValue: Double)

  case object Update
  case class SnakeState(snakeParts: List[SnakePart], size: Double, color: Color)

  // for tests
  case class ReinitSnake(initialY: Double, initialX: Double, initialAngle: Double)

  def props(): Props = Props(new Snake())

  def randomCoordinate(min: Int, max: Int): Double = {
    val delta = max - min
    Random.nextInt(delta / 2) + (delta / 4)
  }
}

class Snake() extends Actor with ActorLogging {

  import Snake._

  private val config = context.system.settings.config
  private val fieldHeight = config.getInt("application.game-field-height")
  private val fieldWidth = config.getInt("application.game-field-width")
  private val initialPartSize = config.getInt("application.initial-snake-part-size")
  private val maximumPartSize = config.getInt("application.maximum-snake-part-size")
  private val initialLength = config.getInt("application.initial-snake-length")
  private val speed = config.getDouble("application.snake-speed")
  private val distanceBetweenParts = config.getDouble("application.distance-between-parts")

  private val headColor: Color = Utils.randomColor()

  private def initSnake(initialY: Double, initialX: Double, initialAngle: Double): List[SnakePart] = {
    val oppositeAngle = (initialAngle + 180) % 360
    SnakePart(initialY, initialX) :: growParts(initialY, initialX, oppositeAngle, initialPartSize, initialLength - 1)
  }

  def growParts(y: Double, x: Double, angle: Double, size: Double, amount: Int): List[SnakePart] = {
    val heightIncrement = size * distanceBetweenParts * Math.cos(Math.toRadians(angle))
    val widthIncrement = size * distanceBetweenParts * Math.sin(Math.toRadians(angle))
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

  def receiveWithState(angle: Double, size: Double, snakeParts: List[SnakePart]): Receive = {
    case ReinitSnake(y: Double, x: Double, a: Double) =>
      context.become(receiveWithState(a, initialPartSize, initSnake(y, x, a)))

    case ChangeAngle(newAngle) =>
      context.become(receiveWithState(newAngle, size, snakeParts), discardOld = true)

    case IncreaseSize(foodValue) =>
      //todo: grow snake not only larger but longer
      if (size < maximumPartSize) {
        context.become(receiveWithState(angle, size + foodValue, snakeParts), discardOld = true)
      }

    case Update =>
      val updatedState = update(angle, size, snakeParts)
      context.become(receiveWithState(angle, size, updatedState), discardOld = true)
      sender() ! SnakeState(updatedState, size, headColor)

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }

  val receiveNotStarted: Receive = {
    case ConnectionHandler.StartGameIn(_) =>
      val initialAngle = Random.nextInt(360)
      receiveWithState(initialAngle, initialPartSize, initSnake(randomCoordinate(0, fieldHeight), randomCoordinate(0, fieldWidth), initialAngle))
  }

  override def receive: Receive = receiveNotStarted

}
