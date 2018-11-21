package com.evolutiongaming.kafka.journal

import cats.Applicative
import com.evolutiongaming.safeakka.actor.ActorLog

trait Log[F[_]] {

  def debug(msg: => String): F[Unit]

  def info(msg: => String): F[Unit]

  def warn(msg: => String): F[Unit]

  def error(msg: => String, cause: Throwable): F[Unit]
}

object Log {

  def empty[F[_]](unit: F[Unit]): Log[F] = new Log[F] {

    def debug(msg: => String) = unit

    def info(msg: => String) = unit

    def warn(msg: => String) = unit

    def error(msg: => String, cause: Throwable) = unit
  }


  def empty[F[_] : Applicative]: Log[F] = empty(Applicative[F].unit)


  def apply[F[_] : IO](log: ActorLog): Log[F] = new Log[F] {

    def debug(msg: => String) = IO[F].effect {
      log.debug(msg)
    }

    def info(msg: => String) = IO[F].effect {
      log.info(msg)
    }

    def warn(msg: => String) = IO[F].effect {
      log.warn(msg)
    }

    def error(msg: => String, cause: Throwable) = IO[F].effect {
      log.error(msg, cause)
    }
  }
}