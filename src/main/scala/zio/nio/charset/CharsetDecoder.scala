package zio
package nio
package charset

import java.nio.charset.{
  CharacterCodingException,
  MalformedInputException,
  UnmappableCharacterException
}
import java.nio.{charset => j}

import zio.nio.core.ByteBuffer
import zio.nio.core.Buffer
import zio.nio.core.CharBuffer
import zio.stream.ZStream.Pull
import zio.stream.{ZStream, ZStreamChunk}

final class CharsetDecoder private (val javaDecoder: j.CharsetDecoder)
    extends AnyVal {

  import CharsetDecoder._

  def averageCharsPerByte: Float = javaDecoder.averageCharsPerByte()

  def charset: Charset = Charset.fromJava(javaDecoder.charset())

  def decode(in: ByteBuffer): IO[j.CharacterCodingException, CharBuffer] =
    in.withJavaBuffer(jBuf =>
        IO.effect(Buffer.charFromJava(javaDecoder.decode(jBuf)))
      )
      .refineToOrDie[j.CharacterCodingException]

  def decode(
      in: ByteBuffer,
      out: CharBuffer,
      endOfInput: Boolean
  ): UIO[CoderResult] = {
    in.withJavaBuffer { jIn =>
      out.withJavaBuffer { jOut =>
        IO.effectTotal(
          CoderResult.fromJava(javaDecoder.decode(jIn, jOut, endOfInput))
        )
      }
    }
  }

  def autoDetect: UIO[AutoDetect] = UIO.effectTotal {
    if (javaDecoder.isAutoDetecting()) {
      if (javaDecoder.isCharsetDetected()) {
        AutoDetect.Detected(Charset.fromJava(javaDecoder.detectedCharset()))
      } else {
        AutoDetect.NotDetected
      }
    } else {
      AutoDetect.NotSupported
    }
  }

  def flush(out: CharBuffer): UIO[CoderResult] = out.withJavaBuffer { jOut =>
    UIO.effectTotal(CoderResult.fromJava(javaDecoder.flush(jOut)))
  }

  def malformedInputAction: UIO[j.CodingErrorAction] =
    UIO.effectTotal(javaDecoder.malformedInputAction())

  def onMalformedInput(errorAction: j.CodingErrorAction): UIO[Unit] =
    UIO.effectTotal(javaDecoder.onMalformedInput(errorAction))

  def unmappableCharacterAction: UIO[j.CodingErrorAction] =
    UIO.effectTotal(javaDecoder.unmappableCharacterAction())

  def onUnmappableCharacter(errorAction: j.CodingErrorAction): UIO[Unit] =
    UIO.effectTotal(javaDecoder.onUnmappableCharacter(errorAction))

  def maxCharsPerByte: Float = javaDecoder.maxCharsPerByte()

  def replacement: UIO[String] = UIO.effectTotal(javaDecoder.replacement())

  def replaceWith(replacement: String): UIO[Unit] =
    UIO.effectTotal(javaDecoder.replaceWith(replacement))

  def reset: UIO[Unit] = UIO.effectTotal(javaDecoder.reset()).unit

  def decodeStream[R, E](
      stream: ZStream[R, E, Chunk[Byte]]
  )(bufSize: Int = 5000): ZStream[R, StreamCodeError[E], Chunk[Char]] = {
    val pull: ZManaged[R, StreamCodeError[E], Pull[R, StreamCodeError[E], Chunk[
      Char
    ]]] = {
      for {
        byteBuffer <- Buffer
          .byte(bufSize)
          .toManaged_
          .orDie
        charBuffer <- Buffer
          .char((bufSize * this.averageCharsPerByte).round)
          .toManaged_
          .orDie
        inPull <- stream.process.mapError(Right(_))
        stateRef <- Ref
          .make[StreamDecodeState](StreamDecodeState.Pull)
          .toManaged_
      } yield {
        def handleCoderResult(coderResult: CoderResult) = coderResult match {
          case CoderResult.Underflow | CoderResult.Overflow =>
            byteBuffer.compact.orDie *>
              charBuffer.flip *>
              charBuffer.getChunk() <*
              charBuffer.clear
          case CoderResult.Malformed(length) =>
            ZIO.fail(Some(Left(new MalformedInputException(length))))
          case CoderResult.Unmappable(length) =>
            ZIO.fail(Some(Left(new UnmappableCharacterException(length))))
        }

        stateRef.get.flatMap {
          case StreamDecodeState.Pull =>
            def decode(
                inBytes: Chunk[Byte]
            ): ZIO[Any, Some[StreamCodeError[E]], Chunk[Char]] =
              for {
                bufRemaining <- byteBuffer.remaining
                (decodeBytes, remainingBytes) = {
                  if (inBytes.length > bufRemaining) {
                    inBytes.splitAt(bufRemaining)
                  } else {
                    (inBytes, Chunk.empty)
                  }
                }
                _ <- byteBuffer.putChunk(decodeBytes).orDie
                _ <- byteBuffer.flip
                result <- this.decode(
                  byteBuffer,
                  charBuffer,
                  endOfInput = false
                )
                decodedChars <- handleCoderResult(result)
                remainderChars <- if (remainingBytes.isEmpty)
                  ZIO.succeed(Chunk.empty)
                else decode(remainingBytes)
              } yield decodedChars ++ remainderChars

            inPull.foldM(
              _.map { e =>
                ZIO.fail(Some(Right(e)))
              }.getOrElse {
                stateRef.set(StreamDecodeState.EndOfInput).as(Chunk.empty)
              },
              decode
            )
          case StreamDecodeState.EndOfInput =>
            for {
              _ <- byteBuffer.flip
              result <- this.decode(
                byteBuffer,
                charBuffer,
                endOfInput = true
              )
              outChars <- handleCoderResult(result)
              _ <- ZIO.when(result == CoderResult.Underflow)(
                stateRef.set(StreamDecodeState.Flush)
              )
            } yield outChars
          case StreamDecodeState.Flush =>
            for {
              result <- this.flush(charBuffer)
              outChars <- result match {
                case CoderResult.Underflow =>
                  charBuffer.flip *> charBuffer.getChunk() <* stateRef.set(
                    StreamDecodeState.Done
                  )
                case CoderResult.Overflow =>
                  charBuffer.flip *> charBuffer.getChunk() <* charBuffer.clear
                case e =>
                  ZIO.dieMessage(
                    s"Error $e should not returned from decoder flush"
                  )
              }
            } yield outChars
          case StreamDecodeState.Done =>
            IO.fail(None)
        }
      }
    }
    ZStream(pull)
  }

}

object CharsetDecoder {

  def fromJava(javaDecoder: j.CharsetDecoder): CharsetDecoder =
    new CharsetDecoder(javaDecoder)

  private sealed abstract class StreamDecodeState

  private object StreamDecodeState {

    case object Pull extends StreamDecodeState

    case object EndOfInput extends StreamDecodeState

    case object Flush extends StreamDecodeState

    case object Done extends StreamDecodeState

  }

}
