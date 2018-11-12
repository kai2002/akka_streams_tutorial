package sample.stream_shared_state

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.math.abs
import scala.util.Random._
import scala.util.Try


/**
  * Stateful stream processing inspired by:
  * https://stackoverflow.com/questions/37902354/akka-streams-state-in-a-flow
  *
  */
object StatefulMapConcat {

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("StatefulMapConcat")
    implicit val materializer = ActorMaterializer()

    //encapsulating your input
    case class IdentValue(id: Int, value: String)

    //some random generated input
    val identValues = List.fill(20)(IdentValue(abs(nextInt()) % 5, "valueHere"))

    val stateFlow = Flow[IdentValue].statefulMapConcat{ () =>
      //state with already processed ids
      var ids = Set.empty[Int]
      identValue => if (ids.contains(identValue.id)) {
        //Do nothing
        println(identValue.value)
        List(identValue)
      } else {
        //Do sth eg download element
        println(identValue)
        ids = ids + identValue.id
        List(identValue)
      }
    }

    Source(identValues)
      .via(stateFlow)
      .runWith(Sink.seq)
      .onComplete {
        case identValues: Try[immutable.Seq[IdentValue]] => println(s"Processed: ${identValues.get.size} elements")
        val result: Map[Int, immutable.Seq[IdentValue]] = identValues.get.groupBy(each => each.id)
          result.foreach{
               each: (Int, immutable.Seq[IdentValue]) =>
                 println(s"ID: ${each._1} elements: ${each._2.size}")
          }
        system.terminate()
      }
  }
}