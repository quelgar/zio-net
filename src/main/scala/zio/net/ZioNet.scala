package zio
package net

import java.io.IOException
import java.nio.channels.{
  ServerSocketChannel => JServerSocketChannel,
  SocketChannel => JSocketChannel
}
import java.util.concurrent.Executors

import zio.internal.Executor
import zio.nio.core.{Buffer, ByteBuffer, InetSocketAddress}
import zio.nio.core.channels.{
  SelectionKey,
  Selector,
  ServerSocketChannel,
  SocketChannel
}
import zio.stream.{Stream, ZSink, ZStream}

import scala.concurrent.ExecutionContext
import scala.language.existentials

sealed trait ReadResult extends Any {

  import ReadResult._

  def toOption: Option[Int] = this match {
    case EndOfStream => None
    case Success(i)  => Some(i)
  }

  def eof: Boolean = this match {
    case EndOfStream => true
    case Success(_)  => false
  }

}

object ReadResult {

  def fromJavaInt(bytesRead: Int): ReadResult =
    if (bytesRead < 0) EndOfStream else Success(bytesRead)

  case object EndOfStream extends ReadResult
  final case class Success(bytesRead: Int) extends AnyVal with ReadResult

}

object ZioNet {

  def default: Managed[IOException, ZioNet[console.Console]] = apply { cause =>
    console.putStrLn(cause.toString)
  }

  def apply[R](
      errorReport: Cause[Throwable] => ZIO[R, Nothing, Unit]
  ): Managed[IOException, ZioNet[R]] = {
    val makeZioNet = for {
      selector <- Selector.make
      workQ <- Queue
        .unbounded[(Promise[E, A], IO[E, A]) forSome { type E; type A }]
      quitRef <- Ref.make[Boolean](false)
    } yield new ZioNet(quitRef, selector, errorReport, workQ)
    makeZioNet.toManaged(_.close)
  }

  private type ServerSocketAttachment =
    ServerSocketChannel => IO[IOException, Unit]

  implicit final class ExtentedZio[-R, +E, +A](val underlying: ZIO[R, E, A])
      extends AnyVal {

    def someError: ZIO[R, Option[E], A] = underlying.mapError(Some(_))

  }

}

/**
  * An event loop for non-blocking network I/O.
  */
final class ZioNet[R] private (
    quitRef: Ref[Boolean],
    selector: Selector,
    errorReport: Cause[Throwable] => ZIO[R, Nothing, Unit],
    loopWork: Queue[(Promise[E, A], IO[E, A]) forSome { type E; type A }]
) {

  import ZioNet._

  /**
    * Dedicated Executor for running the `select`` event loop.
    *
    * The selector loop is very important, and the `select`` call itself is blocking.
    * This executor provides a single dedicated thread for running that loop.
    * This thread can be blocked without effecting other fibers, and there
    * won't be the kind of thread context switch costs that can happen with
    * `zio.blocking`.
    *
    * Note that this means that the event loop _cannot_ fork new fibers.
    * However, this should never be necessary. The code in the event loop
    * should be doing nothing but reading/writing/connecting the ready
    * sockets and then immediately handing off the results via a promise.
    */
  private val executor = Executor.fromExecutionContext(Int.MaxValue)(
    ExecutionContext.fromExecutorService(
      Executors.newSingleThreadExecutor(new Thread(_, "ZioNet run loop"))
    )
  )

  def start: ZIO[R, Nothing, Unit] = {

    def processKey(key: SelectionKey): IO[IOException, Unit] = {
      val program: IO[Option[IOException], Unit] = for {
        readyOps <- key.readyOps.orDie
        javaChannel = key.channel
        attachment <- key.attachment.some
        _ <- ZIO
          .foreach_(readyOps) {
            case SelectionKey.Operation.Accept =>
              val channel = ServerSocketChannel.fromJava(
                javaChannel.asInstanceOf[JServerSocketChannel]
              )
              val socketAttachment =
                attachment.asInstanceOf[ServerSocketAttachment]
              socketAttachment(channel)
            case SelectionKey.Operation.Connect =>
              val promise = attachment.asInstanceOf[Promise[IOException, Unit]]
              key.attach(None) *>
                key.notInterested(SelectionKey.Operation.Connect).orDie *>
                promise.succeed(())
            case SelectionKey.Operation.Read =>
              val socketAttachment =
                attachment.asInstanceOf[Connection.SocketAttachment]
              socketAttachment.readRef.get.flatten
            case SelectionKey.Operation.Write =>
              val socketAttachment =
                attachment.asInstanceOf[Connection.SocketAttachment]
              socketAttachment.writeRef.get.flatten
          }
          .someError
        _ <- selector.removeKey(key).orDie
      } yield ()
      program.optional.unit
    }

    def loop: ZIO[R, Nothing, Unit] = {
      val go = for {
        _ <- selector.select
        quit <- quitRef.get
        _ <- IO.when(quit)(selector.close *> IO.interrupt)
        allWork <- loopWork.takeAll
        _ <- IO.foreach_(allWork) {
          case (promise, action) => action.to(promise)
        }
        selectedKeys <- selector.selectedKeys
        _ <- ZIO.foreach_(selectedKeys)(processKey)
      } yield ()
      go.catchSomeCause {
        case c if !c.interrupted => errorReport(c)
      }.orDie
    }

    loop.forever.lock(executor).fork.unit

  }

  private def close: IO[Nothing, Unit] = {
    quitRef.set(true) *> selector.wakeup.unit
  }

  private def loopThreadRun[A, E](action: IO[E, A]): IO[E, A] = {
    for {
      p <- Promise.make[E, A]
      _ <- loopWork.offer(p -> action)
      _ <- selector.wakeup
      result <- p.await
    } yield result
  }

  def tcpConnection(
      socketAddress: InetSocketAddress
  ): Managed[IOException, Connection] = {
    val program = for {
      connectPromise <- Promise.make[IOException, Unit]
      channel <- SocketChannel.open
      _ <- channel.configureBlocking(block = false)
      finished <- channel.connect(socketAddress)
      _ <- IO.when(finished)(
        IO.dieMessage("socket connection finshed when it shouldn't be!")
      )
      key <- loopThreadRun {
        channel.register(
          selector,
          SelectionKey.Operation.Connect,
          Some(connectPromise)
        )
      }
      readRef <- Ref.make(IO.unit)
      writeRef <- Ref.make(IO.unit)
      attachment = new Connection.SocketAttachment(readRef, writeRef)
      _ <- connectPromise.await
      finished2 <- channel.finishConnect
      _ <- IO.when(!finished2)(IO.dieMessage("socket connection not finshed!"))
      _ <- key.attach(Some(attachment))
    } yield new Connection(key, attachment)
    program.toManaged(_.release)
  }

}

object Connection {
  private[net] final class SocketAttachment(
      val readRef: Ref[IO[Nothing, Unit]],
      val writeRef: Ref[IO[Nothing, Unit]]
  )
}

final class Connection private[net] (
    key: SelectionKey,
    attachment: Connection.SocketAttachment
) {

  private val channel: SocketChannel =
    SocketChannel.fromJava(key.channel.asInstanceOf[JSocketChannel])

  private[net] def release: IO[Nothing, Unit] = {
    val program = for {
      _ <- key.cancel
      _ <- channel.close
    } yield ()
    program.ignore
  }

  /**
    * Sends as many bytes from a buffer as possible without blocking the thread.
    *
    * The number of bytes sent will be >= 0 and <= `buffer.remaining()`.
    * The socket write will never block the thread, meaning that fewer than `buffer.remaining()`
    * bytes may be written if the socket is not ready to accept all the bytes.
    * When this completes, the buffer's position will be advanced to the next byte that
    * hasn't yet been sent. If the position equals the limit, all bytes were sent.
    *
    * @param buffer The buffer to send from.
    * @return The number of bytes written.
    */
  def sendFromBuffer(buffer: ByteBuffer): IO[IOException, Int] = {
    buffer.hasRemaining.flatMap {
      case true =>
        for {
          promise <- Promise.make[IOException, Int]
          writer = channel
            .write(buffer)
            .refineToOrDie[IOException]
            .to(promise)
            .unit <*
            key.notInterested(SelectionKey.Operation.Write).orDie
          _ <- attachment.writeRef.set(writer)
          _ <- key.interested(SelectionKey.Operation.Write).orDie
          _ <- key.selector.wakeup
          count <- promise.await
        } yield count
      case false =>
        IO.succeed(0)
    }

  }

  /**
    * Sends an entire chunk of bytes over the connection.
    *
    * This will perform as many non-thread-blocking socket writes as needed to send
    * all the bytes in the chunk. The current fibre will be blocked until the entire
    * chunk is sent.
    *
    * @param chunk The bytes to send.
    */
  def sendChunk(chunk: Chunk[Byte]): IO[IOException, Unit] = {
    Buffer.byte(chunk).flatMap { buffer =>
      sendFromBuffer(buffer)
        .repeat(Schedule.doWhileM(_ => buffer.hasRemaining))
        .unit
    }
  }

  def sendSink(
      bufferSize: Int = 1000,
      directBuffer: Boolean = false
  ): ZSink[Any, IOException, Chunk[Byte], Chunk[Byte], Unit] = {
    val makeBuffer =
      if (directBuffer) Buffer.byteDirect(bufferSize)
      else Buffer.byte(bufferSize)
    ZSink.fromEffect(makeBuffer.orDie).flatMap { buffer =>
      ZSink.foldM(())(Function.const(true)) { (_, chunk: Chunk[Byte]) =>
        for {
          remainder <- buffer.fillFromChunk(chunk)
          _ <- buffer.flip
          _ <- sendFromBuffer(buffer)
            .repeat(Schedule.doWhileM(_ => buffer.hasRemaining))
          _ <- buffer.compact.orDie
        } yield ((), Chunk.single(remainder))
      }
    }
  }

  def receiveToBuffer(buffer: ByteBuffer): IO[IOException, ReadResult] = {
    for {
      promise <- Promise.make[IOException, ReadResult]
      reader = channel
        .read(buffer)
        .map(ReadResult.fromJavaInt)
        .to(promise)
        .unit <*
        key.notInterested(SelectionKey.Operation.Read).orDie
      _ <- attachment.readRef.set(reader)
      _ <- key.interested(SelectionKey.Operation.Read).orDie
      _ <- key.selector.wakeup
      result <- promise.await
    } yield result
  }

  def receiveStream(
      bufferSize: Int = 4000
  ): ZStream[Any, IOException, Chunk[Byte]] = {
    Stream.fromEffect(Buffer.byte(bufferSize).orDie).flatMap { buffer =>
      def readLoop: Stream[IOException, Chunk[Byte]] = {
        Stream.fromEffect(receiveToBuffer(buffer) <* buffer.flip).flatMap {
          case ReadResult.EndOfStream =>
            Stream.fromEffect(buffer.getChunk())
          case ReadResult.Success(_) =>
            Stream.fromEffect(buffer.getChunk()) ++ readLoop
        }
      }
      readLoop
    }
  }

}
