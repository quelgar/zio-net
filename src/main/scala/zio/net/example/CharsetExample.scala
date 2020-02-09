package zio
package net
package example

import zio.stream._
import zio.nio.charset.{Charset, chunkCollectSink}

object CharsetExample extends App {
  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {

    val test = "إزَّي حضرتك؟"

    val byteStream1 = Stream
      .fromIterable(test.getBytes(Charset.Standard.utf16.javaCharset))
      .map(Chunk.single)
    val charStream1 =
      Charset.Standard.utf16.newDecoder.decodeStream(byteStream1)()

    val (testFirst, testLast) = test.splitAt(5)
    val byteStream2 = Stream(
      Chunk.fromArray(testFirst.getBytes(Charset.Standard.utf8.javaCharset)),
      Chunk.fromArray(testLast.getBytes(Charset.Standard.utf8.javaCharset))
    )
    val charStream2 =
      Charset.Standard.utf8.newDecoder.decodeStream(byteStream2)(4)

    val program = for {
      chunk1 <- charStream1.run(chunkCollectSink[Char])
      s1 = chunk1.mkString
      _ <- console.putStrLn(s1)
      _ <- console.putStrLn(s"1 Matches = ${s1 == test}")
      chunk2 <- charStream2.run(chunkCollectSink[Char])
      s2 = chunk2.mkString
      _ <- console.putStrLn(s"2 Matches = ${s2 == test}")
    } yield ()

    program.foldM(
      e => console.putStrLn(e.toString).as(1),
      _ => ZIO.succeed(0)
    )

  }
}
