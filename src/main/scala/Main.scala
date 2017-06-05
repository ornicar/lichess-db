import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import reactivemongo.api.ReadPreference
import reactivemongo.bson._

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import java.nio.file.Paths
import reactivemongo.akkastream.{State, cursorProducer}

import org.joda.time.DateTime

import chess.format.pgn.Pgn
import db.BSONDateTimeHandler
import lila.game.BSONHandlers._
import lila.game.{Game, PgnDump, Source => S}

object Main extends App {

  override def main(args: Array[String]) {

    val fromStr = args.lift(0).getOrElse("2015-01")
    val from = new DateTime(fromStr).withDayOfMonth(1).withTimeAtStartOfDay()
    val to = from plusMonths 1

    val path = args.lift(1).getOrElse("out/lichess_db_%.pgn").replace("%", fromStr)

    println(s"Export $from to $path")

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    db.getColl foreach {
      case (coll, close) =>

        val sources = List(S.Lobby, S.Friend, S.Tournament, S.Pool)

        val query = BSONDocument(
          "ca" -> BSONDocument("$gte" -> from, "$lt" -> to),
          "ra" -> true,
          "so" -> BSONDocument("$in" -> sources.map(_.id)),
          "v" -> BSONDocument("$exists" -> false))

        type GameOrDate = Either[Game, DateTime]

        val gameSource = coll
          .find(query)
          .sort(BSONDocument("ca" -> 1))
          .cursor[Game]()
          .documentSource(maxDocs = Int.MaxValue)

        val tickSource =
          Source.tick(1.second, 1.second, None)

        def pgnSink: Sink[Pgn, Future[IOResult]] =
          Flow[Pgn]
            .map(pgn => ByteString(s"$pgn\n\n"))
            .toMat(FileIO.toPath(Paths.get(path)))(Keep.right)

        gameSource
          .map(g => Some(g))
          .merge(tickSource, eagerComplete = true)
          .via(new Reporter)
          .map(g => PgnDump(g, None))
          .runWith(pgnSink) andThen {
            case state =>
              close()
              system.terminate()
          }
    }
  }
}