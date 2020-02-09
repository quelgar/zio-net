package zio

package object net {

  private[net] def debug(s: String): IO[Nothing, Unit] =
    IO.effectTotal(println(s"XXX $s"))

}
