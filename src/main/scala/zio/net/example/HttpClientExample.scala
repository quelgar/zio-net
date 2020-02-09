package zio
package net
package example

import zio.nio.charset.Charset
import zio.nio.core.SocketAddress

object HttpClientExample extends App {

  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    ZioNet.default
      .use { zioNet =>
        for {
          address <- SocketAddress.inetSocketAddress("google.com", 80)
          _ <- zioNet.start
          _ <- console.putStrLn("Sending HEAD request to google.com:\n")
          _ <- zioNet.tcpConnection(address).use { connection =>
            val reqLines = List(
              "HEAD / HTTP/1.1",
              "Host: google.com"
            )
            for {
              reqBytes <- Charset.Standard.utf8
                .encodeString(reqLines.mkString("", "\r\n", "\r\n\r\n"))
              _ <- connection.sendChunk(reqBytes)
              stream = Charset.Standard.utf8.newDecoder
                .decodeStream(connection.receiveStream(10000))()
                .aggregate(Framing.delimited(1000, Framing.crLfDelimiter))
                .takeUntil(_.isEmpty)
                .map(_.mkString)
              _ <- stream.foreach(console.putStrLn)
            } yield ()
          }
        } yield ()
      }
      .as(0)
      .catchAll { e =>
        console.putStrLn(s"Failed: $e").as(1)
      }
  }

}
