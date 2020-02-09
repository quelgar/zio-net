package zio
package net

import zio.stream._

object Framing {

  val lfDelimiter = Chunk('\n')

  val crLfDelimiter = Chunk('\r', '\n')

  def delimited[A](
      maxSize: Int,
      delimiter: Chunk[A]
  ): ZSink[Any, Nothing, Chunk[A], Chunk[A], Chunk[A]] = {

    val length = delimiter.length

    def findDelimiter[A](
        pos: Int
    )(chunk: Chunk[A]): Option[(Chunk[A], Chunk[A])] = {

      @scala.annotation.tailrec
      def help(pos: Int): Option[(Chunk[A], Chunk[A])] = {
        val compare = chunk.drop(pos).take(length)
        if (compare.length < length) {
          None
        } else if (compare == delimiter) {
          val (matched, remaining) = chunk.splitAt(pos)
          Some((matched, remaining.drop(length)))
        } else {
          help(pos + 1)
        }
      }

      help(pos)
    }

    ZSink
      .fold((true, Chunk.empty: Chunk[A]))(_._1) { (acc, in: Chunk[A]) =>
        val buffer = acc._2
        val searchBuffer = buffer ++ in
        findDelimiter(math.max(0, buffer.length - length + 1))(searchBuffer)
          .map {
            case (found, remaining) =>
              ((false, found), Chunk.single(remaining))
          }
          .getOrElse {
            if (buffer.length + in.length > maxSize) {
              ((false, searchBuffer), Chunk.empty)
            } else {
              ((true, searchBuffer), Chunk.empty)
            }
          }
      }
      .map(_._2)
  }

}
