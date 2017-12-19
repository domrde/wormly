package wormly

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActors, TestKit}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import wormly.GameClient.SnakePart

class GameClientTest() extends TestKit(ActorSystem()) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A GameClient actor" must {

    "create snake from (5000, 5000) horizontally if angle is 0" in {
      val gameClient = system.actorOf(GameClient.props())
      gameClient ! GameClient.ReinitSnake(5000, 5000, 0)
      gameClient ! GameClient.GetVisibleParts(0, 0, 10000, 10000)
      val parts = expectMsgType[GameClient.VisibleParts]
      val expected = List(SnakePart(5000.0,5000.0), SnakePart(4997.5,5000.0), SnakePart(4995.0,5000.0), SnakePart(4992.5,5000.0), SnakePart(4990.0,5000.0))
      assert(parts.snakeParts == expected)
    }

    "create snake from (5000, 5000) vertically if angle is 90" in {
      val gameClient = system.actorOf(GameClient.props())
      gameClient ! GameClient.ReinitSnake(5000, 5000, 90)
      gameClient ! GameClient.GetVisibleParts(0, 0, 10000, 10000)
      val parts = expectMsgType[GameClient.VisibleParts]
      val expected = List(SnakePart(5000.0,5000.0), SnakePart(5000.0,4997.5), SnakePart(5000.0,4995.0), SnakePart(5000.0,4992.5), SnakePart(5000.0,4990.0))
      assert(parts.snakeParts == expected)
    }

    "move snake vertically if angle is 90" in {
      val gameClient = system.actorOf(GameClient.props())
      gameClient ! GameClient.ReinitSnake(5000, 5000, 90)
      gameClient ! GameClient.Update()
      gameClient ! GameClient.GetVisibleParts(0, 0, 10000, 10000)
      val parts = expectMsgType[GameClient.VisibleParts]
      val expected = List(SnakePart(5000.0,5005.0), SnakePart(5000.0,5002.5), SnakePart(5000.0,5000.0), SnakePart(5000.0,4997.5), SnakePart(5000.0,4995.0))
      assert(parts.snakeParts == expected)
    }

  }
}