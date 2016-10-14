import akka.actor.Actor

/**
  * A simple Sketch Server realized as an actor. It
  * accepts a LocalPDS in its constructor that it executes
  * operations incoming Map and Sketch operations against.
  */
class SketchServerActor[T](localPDS: LocalPDS[T]) extends Actor {
    def receive = {
      case mapper: IMap[T, Any] => {
        localPDS.map (mapper).print()
        sender ! 0
      }
      case sketcher: ISketch[T, Any] => {
        sender ! localPDS.sketch (sketcher)
      }
    }
}
