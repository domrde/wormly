package wormly

import java.awt.Color

import scala.util.Random

object Utils {

  def randomColor(): Color = {
    new Color(Random.nextInt(255), Random.nextInt(255), Random.nextInt(255))
  }

  def colorToString(color: Color): String = {
    s"rgb(${color.getRed},${color.getGreen},${color.getBlue})"
  }

  def actorName(clazz: Class[_]): String = {
    clazz.getSimpleName + Random.nextLong()
  }

}
