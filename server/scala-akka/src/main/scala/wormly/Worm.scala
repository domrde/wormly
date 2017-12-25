package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, Props}

import scala.util.Random

object Worm {

  case class ChangeAngle(newAngle: Double) // radians
  case class WormPart(y: Double, x: Double)
  case class IncreaseSize(foodValue: Double)

  case object Update
  case class WormState(parts: List[WormPart], size: Double, color: Color)

  def props(): Props = Props(new Worm())

  def randomCoordinate(min: Int, max: Int): Double = {
    val delta = max - min
    delta * 0.2 + Random.nextInt(Math.ceil(delta * 0.6).toInt)
  }
}

class Worm() extends Actor with ActorLogging {

  import Worm._

  private val config = context.system.settings.config
  private val fieldHeight = config.getInt("application.game-field.height")
  private val fieldWidth = config.getInt("application.game-field.width")
  private val initialPartSize = config.getInt("application.worm.initial-part-diameter")
  private val maximumPartSize = config.getInt("application.worm.maximum-part-diameter")
  private val distanceBetweenParts = config.getDouble("application.worm.distance-between-parts")
  private val initialLength = config.getInt("application.worm.initial-length")

  private val headColor: Color = Utils.randomColor()

  private def initWorm(initialY: Double, initialX: Double, initialAngle: Double): List[WormPart] = {
    val oppositeAngle = (initialAngle + 180) % 360
    WormPart(initialY, initialX) :: growParts(initialY, initialX, oppositeAngle, initialPartSize, initialLength - 1)
  }

  def growParts(y: Double, x: Double, angle: Double, size: Double, amount: Int): List[WormPart] = {
    val heightIncrement = -size * distanceBetweenParts * Math.sin(angle)
    val widthIncrement = -size * distanceBetweenParts * Math.cos(angle)
    (1 to amount).map { idx =>
      WormPart(
        y + idx * heightIncrement,
        x + idx * widthIncrement
      )
    }.toList
  }

  def update(angle: Double, size: Double, wormParts: List[WormPart]): List[WormPart] = {
    growParts(wormParts.head.y, wormParts.head.x, angle, size, 1).reverse ::: wormParts.dropRight(1)
  }

  def receiveWithState(angle: Double, size: Double, wormParts: List[WormPart]): Receive = {
    case ChangeAngle(newAngle) =>
      context.become(receiveWithState(newAngle, size, wormParts), discardOld = true)

    case IncreaseSize(foodValue) =>
      //todo: grow worm not only larger but longer
      if (size < maximumPartSize) {
        context.become(receiveWithState(angle, size + foodValue / size, wormParts), discardOld = true)
      }

    case Update =>
      val updatedState = update(angle, size, wormParts)
      context.become(receiveWithState(angle, size, updatedState), discardOld = true)
      sender() ! WormState(updatedState, size, headColor)

    case GameClient.MoveWormImmediately(y, x, a, s, parts) =>
      val oppositeAngle = (a + 180) % 360
      val worm = WormPart(y, x) :: growParts(y, x, oppositeAngle, s, parts - 1)
      context.become(receiveWithState(a, s, worm))

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }

  override def receive: Receive = {
    val initialAngle = Random.nextInt(360)
    receiveWithState(
      initialAngle,
      initialPartSize,
      initWorm(randomCoordinate(0, fieldHeight), randomCoordinate(0, fieldWidth), initialAngle)
    )
  }

}
