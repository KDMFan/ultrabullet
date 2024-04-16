package lila.tree

import chess.format.Fen
import chess.format.pgn.{ ParsedPgn, Parser, PgnStr, Reader, Sans }
import chess.{ Game as ChessGame, * }
import chess.variant.*
import scala.util.chaining.*

import lila.core.userId.UserId
import lila.core.game.{ Game, NewGame }

case class TagResult(status: Status, winner: Option[Color]):
  // duplicated from Game.finish
  def finished = status >= Status.Mate

case class ImportData(pgn: PgnStr, analyse: Option[String])
case class ImportReady(game: NewGame, replay: Replay, initialFen: Option[Fen.Full], parsed: ParsedPgn)
case class ImportReady2(
    game: ChessGame,
    result: Option[TagResult],
    replay: Replay,
    initialFen: Option[Fen.Full],
    parsed: ParsedPgn
)

type ParseImport = (ImportData, Option[UserId]) => Either[ErrorStr, ImportReady]

trait Importer:
  val parseImport: ParseImport
  def importAsGame(data: ImportData, forceId: Option[GameId] = none)(using Option[MyId]): Fu[Game]

private val maxPlies = 600

val parseImport: (ImportData, Option[UserId]) => Either[ErrorStr, ImportReady2] = (data, user) =>
  catchOverflow: () =>
    Parser.full(data.pgn).map { parsed =>
      Reader
        .fullWithSans(parsed, _.map(_.take(maxPlies)))
        .pipe:
          case Reader.Result.Complete(replay)      => replay
          case Reader.Result.Incomplete(replay, _) => replay
        .pipe { case replay @ Replay(setup, _, state) =>
          val initBoard    = parsed.tags.fen.flatMap(Fen.read).map(_.board)
          val fromPosition = initBoard.nonEmpty && !parsed.tags.fen.exists(_.isInitial)
          val variant = {
            parsed.tags.variant | {
              if fromPosition then FromPosition
              else Standard
            }
          } match
            case Chess960 if !isChess960StartPosition(setup.situation) =>
              FromPosition
            case FromPosition if parsed.tags.fen.isEmpty => Standard
            case Standard if fromPosition                => FromPosition
            case v                                       => v
          val game = state.copy(situation = state.situation.withVariant(variant))
          val initialFen = parsed.tags.fen
            .flatMap(Fen.readWithMoveNumber(variant, _))
            .map(Fen.write)

          val status = parsed.tags(_.Termination).map(_.toLowerCase) match
            case Some("normal")                           => game.situation.status | Status.Resign
            case Some("abandoned")                        => Status.Aborted
            case Some("time forfeit")                     => Status.Outoftime
            case Some("rules infraction")                 => Status.Cheat
            case Some(txt) if txt.contains("won on time") => Status.Outoftime
            case _                                        => Status.UnknownFinish

          val result = parsed.tags.outcome
            .map:
              case Outcome(Some(winner))           => TagResult(status, winner.some)
              case _ if status == Status.Outoftime => TagResult(status, none)
              case _                               => TagResult(Status.Draw, none)
            .filter(_.status > Status.Started)
            .orElse { game.situation.status.map(TagResult(_, game.situation.winner)) }

          ImportReady2(game, result, replay.copy(state = game), initialFen, parsed)
        }
    }

private def isChess960StartPosition(sit: Situation) =
  import _root_.chess.*
  val strict =
    def rankMatches(f: Option[Piece] => Boolean)(rank: Rank) =
      File.all.forall: file =>
        f(sit.board(file, rank))
    rankMatches {
      case Some(Piece(White, King | Queen | Rook | Knight | Bishop)) => true
      case _                                                         => false
    }(Rank.First) &&
    rankMatches {
      case Some(Piece(White, Pawn)) => true
      case _                        => false
    }(Rank.Second) &&
    List(Rank.Third, Rank.Fourth, Rank.Fifth, Rank.Sixth).forall(rankMatches(_.isEmpty)) &&
    rankMatches {
      case Some(Piece(Black, Pawn)) => true
      case _                        => false
    }(Rank.Seventh) &&
    rankMatches {
      case Some(Piece(Black, King | Queen | Rook | Knight | Bishop)) => true
      case _                                                         => false
    }(Rank.Eighth)

  Chess960.valid(sit, strict)

private def catchOverflow[A](f: () => Either[ErrorStr, A]): Either[ErrorStr, A] =
  try f()
  catch
    case e: RuntimeException if e.getMessage.contains("StackOverflowError") =>
      ErrorStr("This PGN seems too long or too complex!").asLeft
