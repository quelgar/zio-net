package zio
package net
package example

import stream._

object FramingExample extends App {

  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    val s = """
This
is
a
test
"""
    val (chunk1, chunk2) = Chunk.fromIterable(s).splitAt(6)
    val stream1 = ZStream(chunk1, chunk2).aggregate(
      Framing.delimited(1000, Framing.lfDelimiter)
    )

    val stream2 = ZStream(
      Chunk.fromIterable("123<!>ABC<!"),
      Chunk.fromIterable(">XYZ")
    ).aggregate(Framing.delimited(1000, Chunk('<', '!', '>')))

    val stream = stream1 ++ stream2
    stream
      .foreach(chunk => console.putStrLn(chunk.mkString("[", "", "]")))
      .as(0)

    val stream3 = Stream(
      Chunk("aaa\nb"),
      Chunk("bb\nccc\nddd\nlong"),
      Chunk(" line"),
      Chunk("\nend")
    )
    stream3
      .aggregate(Sink.splitLinesChunk)
      .foreach(chunk => console.putStrLn(chunk.mkString("[", "", "]")))
      .as(0)

  }

}
