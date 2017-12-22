package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import wormly.ConnectionHandler.{CanvasSize, FoodOut, SnakePartOut}
import wormly.SequentialOperationsManager.Food
import wormly.Snake.{SnakePart, SnakeState}

object OutputDataMapper {

  case class FilterVisibleObjects(snakes: Map[ActorRef, SnakeState], foodList: List[Food], canvasSize: CanvasSize)
  case class ConversionInfo(offsetY: Double, offsetX: Double,
                            canvasHeight: Double, canvasWidth: Double,
                            sizeMultiplier: Double,
                            upperBound: Double, leftBound: Double, lowerBound: Double, rightBound: Double)
  case class VisibleParts(snakeParts: List[SnakePart], size: Double, color: Color)
  case class VisibleObjects(snakeParts: List[VisibleParts], food: List[Food])

  def props(): Props = Props(new OutputDataMapper())
}

class OutputDataMapper extends Actor with ActorLogging {
  import OutputDataMapper._

  private val config = context.system.settings.config
  private val snakeHeadsInCanvasHeight = config.getInt("application.game-field.snake-heads-in-canvas-height")

  def calculateVisibilityWindow(head: SnakePart, size: Double, canvasSize: CanvasSize): ConversionInfo = {
    val halfHeight = snakeHeadsInCanvasHeight / 2.0
    ConversionInfo(
      offsetY = head.y,
      offsetX = head.x,
      canvasHeight = canvasSize.height,
      canvasWidth = canvasSize.width,
      sizeMultiplier = canvasSize.height / snakeHeadsInCanvasHeight / size,
      upperBound = head.y - halfHeight * size,
      leftBound = head.x - halfHeight * canvasSize.width / canvasSize.height * size,
      lowerBound = head.y + halfHeight * size,
      rightBound = head.x + halfHeight * canvasSize.width / canvasSize.height * size
    )
  }

  def mapToClientCoordinates(y: Double, x: Double, d: Double, info: ConversionInfo): (Double, Double, Double) = {
    val localY = info.sizeMultiplier * (y - info.offsetY) + info.canvasHeight / 2.0
    val localX = info.sizeMultiplier * (x - info.offsetX) + info.canvasWidth / 2.0
    val diam = d * info.sizeMultiplier
    (localY, localX, diam)
  }

  def filterAndMapSnakes(snakes: Map[ActorRef, SnakeState], window: ConversionInfo): List[SnakePartOut] = {
    snakes.flatMap { case (_, snake) =>
      snake.snakeParts.filter { part =>
        val radius = snake.size / 2.0
        window.upperBound < part.y + radius && part.y - radius < window.lowerBound &&
          window.leftBound < part.x + radius && part.x - radius < window.rightBound
      }.map { part =>
        val clientCoordinates = mapToClientCoordinates(part.y, part.x, snake.size, window)
        SnakePartOut(clientCoordinates._1, clientCoordinates._2, clientCoordinates._3, Utils.colorToString(snake.color))
      }
    }.toList
  }

  def filterAndMapFood(foodList: List[Food], window: ConversionInfo): List[FoodOut] = {
    foodList.filter { food =>
      val radius = food.d / 2.0
      window.upperBound < food.y + radius && food.y - radius < window.lowerBound &&
        window.leftBound < food.x + radius && food.x - radius < window.rightBound
    }.map { food =>
      val clientCoordinates = mapToClientCoordinates(food.y, food.x, food.d, window)
      FoodOut(clientCoordinates._1, clientCoordinates._2, clientCoordinates._3, Utils.colorToString(food.color))
    }
  }

  override def receive: Receive = {
    case FilterVisibleObjects(snakes, foodList, canvasSize) =>
      val senderSnake = snakes(sender())
      val visibilityWindow = calculateVisibilityWindow(senderSnake.snakeParts.head, senderSnake.size, canvasSize)
      sender() ! ConnectionHandler.VisibleObjectsOut(
        filterAndMapSnakes(snakes, visibilityWindow),
        filterAndMapFood(foodList, visibilityWindow),
        visibilityWindow.offsetY,
        visibilityWindow.offsetX
      )

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}
