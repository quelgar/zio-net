# ZIO-NET

**A non-blocking network I/O library for ZIO programs.**

Using [ZIO-NIO][zio-nio], ZIO-NET provides a non-blocking event loop for network I/O, based on [Java's NIO Selector class][java-selector]. It is little more than a fun experiment at this point.

**Note:** at this time, ZIO-NET is built using a [slightly modified version of ZIO-NIO][modified-zio-nio]. Hopefully these changes will soon be incorporated into a ZIO-NIO release.

The core of the library is the [ZioNet](src/main/scala/zio/net/ZioNet.scala) class. There's a very basic example of printing the headers from an HTTP `HEAD` request in [HttpClientExample](src/main/scala/zio/net/example/HttpClientExample.scala)

## Philosophy

We want to perform network I/O from our ZIO programs. There are a number of good options for doing this:

* Use Cats-Effect interoperability to use existing non-ZIO libraries, for example [http4s][http4s]. The main downside is that we lose some of the nice properties of ZIO when converting to Cats-Effect.
* Implement ZIO abstractions on top of the [Java `AsynchronousSocketChannel`][java-async-sock]. This would be a fine approach, but the internals of this API seem rather opaque and complex. While it does appear to use non-blocking I/O, it also maintains its own thread pool for reasons that aren't entirely obvious.
* Implement ZIO abstractions on top of [Netty][netty]. This may prove to be the best approach, since Netty can't be beaten for low-level performance, and should provide everything needed to implement ZIO abstractions efficiently.

ZIO-NET is an alternative approach that pushes ZIO abstractions as low down as possible. For example, this means the event loop calling `select` and processing ready sockets is built using `ZIO`, and is purely functional.

The cost of this approach is that ZIO-NET can never equal the performance of Netty. While ZIO puts a great deal of effort into achieving good performance, the overhead of using an effect type on the JVM can never be completely eliminated.

The theory ZIO-NET aims to test is that using ZIO all the way down to NIO will produce advantages for ZIO developers, which makes the performance hit worth it:

* ZIO tracing will cover the network layer as well, aiding troubleshooting
* The network layer can use the same ZIO runtime as the rest of the program. No need to also maintain a Netty thread pool, for example.
* If the network layer is purpose-built to support ZIO abstractions, it may end up performing well for real-world ZIO applications, even if the raw low-level performance is worse.

## FAQs

**Q1.** What about non-blocking disk I/O?

Non-blocking disk I/O is not really a thing (there are some obscure exceptions, but they're of no practical use to a Scala developer). Both [Java's `AsynchronousFileChannel`][java-async-file] and [libuv's file API][libuv-file] are faking it with thread pools.

It's questionable whether `AsynchronousFileChannel` is worth using for ZIO programs. The old-school blocking APIs with [ZIO's `blocking` package][zio-blocking] will work just as well, and it avoids dealing with a semi-hidden thread pool maintained by the Java library.


[zio-nio]: https://zio.github.io/zio-nio/
[http4s]: https://http4s.org
[netty]: https://netty.io
[java-selector]: https://docs.oracle.com/javase/8/docs/api/java/nio/channels/Selector.html
[java-async-sock]: https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousSocketChannel.html
[java-async-file]: https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousFileChannel.html
[libuv-file]: http://docs.libuv.org/en/v1.x/guide/filesystem.html
[zio-blocking]: https://javadoc.io/doc/dev.zio/zio_2.12/latest/zio/blocking/index.html
[modified-zio-nio]: https://github.com/zio/zio-nio/tree/quelgar/improvements
