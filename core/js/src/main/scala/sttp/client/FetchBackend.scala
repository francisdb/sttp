package sttp.client

import org.scalajs.dom.experimental.{BodyInit, Response => FetchResponse}
import sttp.client.monad.FutureMonad

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Promise
import org.scalajs.dom.experimental.{Request => FetchRequest}

class FetchBackend private (fetchOptions: FetchOptions, customizeRequest: FetchRequest => FetchRequest)(
    implicit ec: ExecutionContext
) extends AbstractFetchBackend[Future, Nothing](fetchOptions, customizeRequest)(new FutureMonad()) {
  override protected def addCancelTimeoutHook[T](result: Future[T], cancel: () => Unit): Future[T] = {
    result.onComplete(_ => cancel())
    result
  }

  override protected def handleStreamBody(s: Nothing): Future[js.UndefOr[BodyInit]] = {
    // we have an instance of nothing - everything's possible!
    Future.successful(js.undefined)
  }

  override protected def handleResponseAsStream[T](
      ras: ResponseAsStream[T, Nothing],
      response: FetchResponse
  ): Future[Nothing] = {
    throw new IllegalStateException("Future FetchBackend does not support streaming responses")
  }

  override protected def transformPromise[T](promise: => Promise[T]): Future[T] = promise.toFuture
}

object FetchBackend {
  def apply(
      fetchOptions: FetchOptions = FetchOptions.Default,
      customizeRequest: FetchRequest => FetchRequest = identity
  )(implicit ec: ExecutionContext = ExecutionContext.Implicits.global): SttpBackend[Future, Nothing, NothingT] =
    new FetchBackend(fetchOptions, customizeRequest)
}
