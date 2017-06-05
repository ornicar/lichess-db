package lila.game

import org.joda.time.DateTime
import scala.collection.breakOut
import scala.collection.Searching._
import scala.collection.breakOut
import scala.util.Try

import chess._
import chess.variant.Variant

import lila.db.ByteArray

import org.lichess.clockencoder.{ Encoder => ClockEncoder }

object BinaryFormat {

  object pgn {

    def write(moves: PgnMoves): ByteArray = ???

    def read(ba: ByteArray): PgnMoves =
      format.pgn.Binary.readMoves(ba.value.toList).get

    def read(ba: ByteArray, nb: Int): PgnMoves =
      format.pgn.Binary.readMoves(ba.value.toList, nb).get
  }

  object clockHistory {

    def writeSide(start: Centis, times: Vector[Centis], flagged: Boolean) = ???

    def readSide(start: Centis, ba: ByteArray, flagged: Boolean) = {
      val decoded: Vector[Centis] = ClockEncoder.decode(ba.value, start.centis).map(Centis.apply)(breakOut)
      if (flagged) decoded :+ Centis(0) else decoded
    }

    def read(start: Centis, bw: ByteArray, bb: ByteArray, flagged: Option[Color], gameId: String) = Try {
      ClockHistory(
        readSide(start, bw, flagged contains White),
        readSide(start, bb, flagged contains Black)
      )
    }.fold(
      e => { println(s"Exception decoding history on game $gameId", e); none },
      some
    )
  }

  object moveTime {

    private type MT = Int // centiseconds
    private val size = 16
    private val buckets = List(10, 50, 100, 150, 200, 300, 400, 500, 600, 800, 1000, 1500, 2000, 3000, 4000, 6000)
    private val encodeCutoffs = buckets zip buckets.tail map {
      case (i1, i2) => (i1 + i2) / 2
    } toVector

    private val decodeMap: Map[Int, MT] = buckets.zipWithIndex.map(x => x._2 -> x._1)(breakOut)

    def write(mts: Vector[Centis]): ByteArray = ???

    def read(ba: ByteArray, turns: Int): Vector[Centis] = {
      def dec(x: Int) = decodeMap get x getOrElse decodeMap(size - 1)
      ba.value map toInt flatMap { k =>
        Array(dec(k >> 4), dec(k & 15))
      }
    }.take(turns).map(Centis.apply)(breakOut)
  }

  case class clock(start: Timestamp) {
    def write(clock: Clock): ByteArray = ???

    def read(ba: ByteArray, whiteBerserk: Boolean, blackBerserk: Boolean): Color => Clock = color => {
      val ia = ba.value map toInt

      // ba.size might be greater than 12 with 5 bytes timers
      // ba.size might be 8 if there was no timer.
      // #TODO remove 5 byte timer case! But fix the DB first!
      val timer = {
        if (ia.size == 12) readTimer(readInt(ia(8), ia(9), ia(10), ia(11)))
        else None
      }

      ia match {
        case Array(b1, b2, b3, b4, b5, b6, b7, b8, _*) => {
          val config = Clock.Config(readClockLimit(b1), b2)
          val whiteTime = Centis(readSignedInt24(b3, b4, b5))
          val blackTime = Centis(readSignedInt24(b6, b7, b8))
          timer.fold[Clock](
            PausedClock(
              config = config,
              color = color,
              whiteTime = whiteTime,
              blackTime = blackTime,
              whiteBerserk = whiteBerserk,
              blackBerserk = blackBerserk
            )
          )(t =>
              RunningClock(
                config = config,
                color = color,
                whiteTime = whiteTime,
                blackTime = blackTime,
                whiteBerserk = whiteBerserk,
                blackBerserk = blackBerserk,
                timer = t
              ))
        }
        case _ => sys error s"BinaryFormat.clock.read invalid bytes: ${ba.showBytes}"
      }
    }

    private def readTimer(l: Int) =
      if (l != 0) Some(start + Centis(l)) else None

    private def writeClockLimit(limit: Int): Byte = ???

    private def readClockLimit(i: Int) = {
      if (i < 181) i * 60 else (i - 180) * 15
    }
  }

  object clock {
    def apply(start: DateTime) = new clock(Timestamp(start.getMillis))
  }

  object castleLastMoveTime {

    def write(clmt: CastleLastMoveTime): ByteArray = ???

    def read(ba: ByteArray): CastleLastMoveTime = {
      val ints = ba.value map toInt
      val size = ints.size

      if (size < 2 || size > 6) sys error s"BinaryFormat.clmt.read invalid: ${ba.showBytes}"
      val checkByte = if (size == 6 || size == 3) ints.lastOption else None

      doRead(ints(0), ints(1), checkByte)
    }

    private def posAt(x: Int, y: Int) = Pos.posAt(x + 1, y + 1)

    private def doRead(b1: Int, b2: Int, checkByte: Option[Int]) =
      CastleLastMoveTime(
        castles = Castles(b1 > 127, (b1 & 64) != 0, (b1 & 32) != 0, (b1 & 16) != 0),
        lastMove = for {
          from ← posAt((b1 & 15) >> 1, ((b1 & 1) << 2) + (b2 >> 6))
          to ← posAt((b2 & 63) >> 3, b2 & 7)
          if from != Pos.A1 || to != Pos.A1
        } yield from -> to,
        check = checkByte flatMap { x => posAt(x >> 3, x & 7) }
      )
  }

  object piece {

    private val groupedPos = Pos.all grouped 2 collect {
      case List(p1, p2) => (p1, p2)
    } toArray

    def write(pieces: PieceMap): ByteArray = ???

    def read(ba: ByteArray, variant: Variant): PieceMap = {
      def splitInts(b: Byte) = {
        val int = b.toInt
        Array(int >> 4, int & 0x0F)
      }
      def intPiece(int: Int): Option[Piece] =
        intToRole(int & 7, variant) map { role => Piece(Color((int & 8) == 0), role) }
      val pieceInts = ba.value flatMap splitInts
      (Pos.all zip pieceInts).flatMap {
        case (pos, int) => intPiece(int) map (pos -> _)
      }(breakOut)
    }

    private def intToRole(int: Int, variant: Variant): Option[Role] = int match {
      case 6 => Some(Pawn)
      case 1 => Some(King)
      case 2 => Some(Queen)
      case 3 => Some(Rook)
      case 4 => Some(Knight)
      case 5 => Some(Bishop)
      // Legacy from when we used to have an 'Antiking' piece
      case 7 if variant.antichess => Some(King)
      case _ => None
    }
    private def roleToInt(role: Role): Int = role match {
      case Pawn => 6
      case King => 1
      case Queen => 2
      case Rook => 3
      case Knight => 4
      case Bishop => 5
    }
  }

  object unmovedRooks {

    val emptyByteArray = ByteArray(Array(0, 0))

    def write(o: UnmovedRooks): ByteArray = ???

    private def bitAt(n: Int, k: Int) = (n >> k) & 1

    private val arrIndexes = 0 to 1
    private val bitIndexes = 0 to 7
    private val whiteStd = Set(Pos.A1, Pos.H1)
    private val blackStd = Set(Pos.A8, Pos.H8)

    def read(ba: ByteArray) = UnmovedRooks {
      var set = Set.empty[Pos]
      arrIndexes.foreach { i =>
        val int = ba.value(i).toInt
        if (int != 0) {
          if (int == -127) set = if (i == 0) whiteStd else set ++ blackStd
          else bitIndexes.foreach { j =>
            if (bitAt(int, j) == 1) set = set + Pos.posAt(8 - j, 1 + 7 * i).get
          }
        }
      }
      set
    }
  }

  @inline private def toInt(b: Byte): Int = b & 0xff

  def writeInt24(int: Int) = {
    val i = if (int < (1 << 24)) int else 0
    Array((i >>> 16).toByte, (i >>> 8).toByte, i.toByte)
  }

  private val int23Max = 1 << 23
  def writeSignedInt24(int: Int) = {
    val i = if (int < 0) int23Max - int else math.min(int, int23Max)
    writeInt24(i)
  }

  def readInt24(b1: Int, b2: Int, b3: Int) = (b1 << 16) | (b2 << 8) | b3

  def readSignedInt24(b1: Int, b2: Int, b3: Int) = {
    val i = readInt24(b1, b2, b3)
    if (i > int23Max) int23Max - i else i
  }

  def writeInt(i: Int) = Array(
    (i >>> 24).toByte, (i >>> 16).toByte, (i >>> 8).toByte, i.toByte
  )

  def readInt(b1: Int, b2: Int, b3: Int, b4: Int) = {
    (b1 << 24) | (b2 << 16) | (b3 << 8) | b4
  }
}