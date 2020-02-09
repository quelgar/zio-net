package zio
package nio

import java.nio.{charset => j}

import zio.stream.Sink

package object charset {

  type StreamCodeError[+E] = Either[j.CharacterCodingException, E]

  sealed abstract class AutoDetect

  object AutoDetect {

    case object NotSupported extends AutoDetect
    case object NotDetected extends AutoDetect
    final case class Detected(charset: Charset) extends AutoDetect

  }

  def chunkCollectSink[A]: Sink[Nothing, Nothing, Chunk[A], Chunk[A]] =
    Sink.foldLeft(Chunk[A]())(_ ++ _)

}
