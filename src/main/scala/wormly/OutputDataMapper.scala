package wormly

import java.awt.Color

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import wormly.ConnectionHandler.{CanvasSize, FoodOut, WormPartOut}
import wormly.SequentialOperationsManager.Food
import wormly.Worm.{WormPart, WormState}

object OutputDataMapper {

  case class FilterVisibleObjects(worms: Map[ActorRef, WormState], food: Set[Food], canvasSize: CanvasSize)

  case class ConversionInfo(offsetY: Double, offsetX: Double,
                            canvasHeight: Double, canvasWidth: Double,
                            sizeMultiplier: Double,
                            upperBound: Double, leftBound: Double, lowerBound: Double, rightBound: Double)

  case class VisibleParts(wormParts: List[WormPart], size: Double, color: Color)

  case class VisibleObjects(wormParts: List[VisibleParts], food: List[Food])

  def props(): Props = Props(new OutputDataMapper())
}

class OutputDataMapper extends Actor with ActorLogging {

  import OutputDataMapper._

  private val config = context.system.settings.config
  private val wormHeadsInCanvasHeight = config.getInt("application.game-field.worm-heads-in-canvas-height")

  def calculateVisibilityWindow(head: WormPart, size: Double, canvasSize: CanvasSize): ConversionInfo = {
    val halfHeight = wormHeadsInCanvasHeight / 2.0
    ConversionInfo(
      offsetY = head.y,
      offsetX = head.x,
      canvasHeight = canvasSize.height,
      canvasWidth = canvasSize.width,
      sizeMultiplier = canvasSize.height / wormHeadsInCanvasHeight / size,
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

  def filterAndMapWorms(worms: Map[ActorRef, WormState], window: ConversionInfo): List[WormPartOut] = {
    worms.flatMap { case (_, worm) =>
      worm.parts.filter { part =>
        val radius = worm.size / 2.0
        window.upperBound < part.y + radius && part.y - radius < window.lowerBound &&
          window.leftBound < part.x + radius && part.x - radius < window.rightBound
      }.map { part =>
        val clientCoordinates = mapToClientCoordinates(part.y, part.x, worm.size, window)
        WormPartOut(clientCoordinates._1, clientCoordinates._2, clientCoordinates._3, Utils.colorToString(worm.color))
      }
    }.toList
  }

  def filterAndMapFood(foodSet: Set[Food], window: ConversionInfo): Set[FoodOut] = {
    foodSet.filter { food =>
      val radius = food.d / 2.0
      window.upperBound < food.y + radius && food.y - radius < window.lowerBound &&
        window.leftBound < food.x + radius && food.x - radius < window.rightBound
    }.map { food =>
      val clientCoordinates = mapToClientCoordinates(food.y, food.x, food.d, window)
      FoodOut(clientCoordinates._1, clientCoordinates._2, clientCoordinates._3, Utils.colorToString(food.color))
    }
  }

  def generateGrid(info: ConversionInfo): (List[Double], List[Double]) = {
    (
      (Math.round(info.leftBound) to Math.round(info.rightBound) by 1L)
        .filter(i => i % 50 == 0)
        .map(x => info.sizeMultiplier * (x - info.offsetX) + info.canvasWidth / 2.0)
        .toList,

      (Math.round(info.upperBound) to Math.round(info.lowerBound) by 1L)
        .filter(i => i % 50 == 0)
        .map(y => info.sizeMultiplier * (y - info.offsetY) + info.canvasHeight / 2.0)
        .toList
    )
  }

  override def receive: Receive = {
    case FilterVisibleObjects(worms, foodSet, canvasSize) =>
      val senderWorm = worms(sender())
      val visibilityWindow = calculateVisibilityWindow(senderWorm.parts.head, senderWorm.size, canvasSize)
      val grid = generateGrid(visibilityWindow)
      sender() ! ConnectionHandler.VisibleObjectsOut(
        filterAndMapWorms(worms, visibilityWindow),
        filterAndMapFood(foodSet, visibilityWindow),
        grid._1,
        grid._2,
        visibilityWindow.offsetY.toInt,
        visibilityWindow.offsetX.toInt
      )

    case other =>
      log.error("Unexpected message {} from {}", other, sender())
  }
}
