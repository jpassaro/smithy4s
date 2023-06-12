/*
 *  Copyright 2021-2022 Disney Streaming
 *
 *  Licensed under the Tomorrow Open Source Technology License, Version 1.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     https://disneystreaming.github.io/TOST-1.0.txt
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package smithy4s.http4s.kernel

import cats.~>
import org.http4s.Request
import org.http4s.Header
import org.http4s.headers.`Content-Encoding`
import org.http4s.headers.`Content-Length`
import fs2.compression.Compression
import fs2.compression.DeflateParams
import fs2.compression.ZLibParams

// inspired from:
// https://github.com/http4s/http4s/blob/v0.23.19/client/shared/src/main/scala/org/http4s/client/middleware/GZip.scala
object GzipRequestEncoder {
  val DefaultBufferSize = 32 * 1024

  def apply[F[_]: Compression](
      bufferSize: Int = DefaultBufferSize,
      level: DeflateParams.Level = DeflateParams.Level.DEFAULT
  ): RequestEncoder.MiddlewareK[F] =
    new (RequestEncoder[F, *] ~> RequestEncoder[F, *]) {
      def apply[A](fa: RequestEncoder[F, A]): RequestEncoder[F, A] = {
        val a = make[F, A](bufferSize, level)
        RequestEncoder.combine(fa, a)
      }
    }

  def make[F[_]: Compression, A](
      bufferSize: Int,
      level: DeflateParams.Level
  ): RequestEncoder[F, A] =
    new RequestEncoder[F, A] {
      def addToRequest(request: Request[F], a: A): Request[F] = {
        val updateContentTypeEncoding =
          request.headers.get[`Content-Encoding`] match {
            case None =>
              Header.Raw(
                `Content-Encoding`.headerInstance.name,
                "gzip"
              )
            case Some(`Content-Encoding`(cc)) =>
              Header.Raw(
                `Content-Encoding`.headerInstance.name,
                s"${cc.coding}, gzip"
              )
          }
        val compressPipe =
          Compression[F].gzip(
            fileName = None,
            modificationTime = None,
            comment = None,
            DeflateParams(
              bufferSize = bufferSize,
              level = level,
              header = ZLibParams.Header.GZIP
            )
          )
        request
          .removeHeader[`Content-Length`]
          .putHeaders(updateContentTypeEncoding)
          .withBodyStream(request.body.through(compressPipe))
      }
    }
}
