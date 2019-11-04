package sttp.client.prometheus

import java.util.concurrent.ConcurrentHashMap

import com.github.ghik.silencer.silent
import sttp.client.{FollowRedirectsBackend, NothingT, Request, Response, SttpBackend}
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import sttp.client.monad.MonadError
import sttp.client.ws.WebSocketResponse

import scala.collection.mutable
import scala.language.higherKinds

class PrometheusBackend[F[_], S] private (
    delegate: SttpBackend[F, S, NothingT],
    requestToHistogramNameMapper: Request[_, S] => Option[CollectorNameWithLabels],
    requestToInProgressGaugeNameMapper: Request[_, S] => Option[CollectorNameWithLabels],
    requestToSuccessCounterMapper: Request[_, S] => Option[CollectorNameWithLabels],
    requestToErrorCounterMapper: Request[_, S] => Option[CollectorNameWithLabels],
    requestToFailureCounterMapper: Request[_, S] => Option[CollectorNameWithLabels],
    collectorRegistry: CollectorRegistry,
    histogramsCache: ConcurrentHashMap[String, Histogram],
    gaugesCache: ConcurrentHashMap[String, Gauge],
    countersCache: ConcurrentHashMap[String, Counter]
) extends SttpBackend[F, S, NothingT] {
  override def send[T](request: Request[T, S]): F[Response[T]] = {
    val requestTimer: Option[Histogram.Timer] = for {
      histogramData <- requestToHistogramNameMapper(request)
      histogram: Histogram = getOrCreateMetric(histogramsCache, histogramData, createNewHistogram)
    } yield histogram.labels(histogramData.labelValues: _*).startTimer()

    val gauge: Option[Gauge.Child] = for {
      gaugeData <- requestToInProgressGaugeNameMapper(request)
    } yield getOrCreateMetric(gaugesCache, gaugeData, createNewGauge).labels(gaugeData.labelValues: _*)

    gauge.foreach(_.inc())

    responseMonad.handleError(
      responseMonad.map(delegate.send(request)) { response =>
        requestTimer.foreach(_.observeDuration())
        gauge.foreach(_.dec())

        if (response.isSuccess) {
          incCounterIfMapped(request, requestToSuccessCounterMapper)
        } else {
          incCounterIfMapped(request, requestToErrorCounterMapper)
        }

        response
      }
    ) {
      case e: Exception =>
        requestTimer.foreach(_.observeDuration())
        gauge.foreach(_.dec())
        incCounterIfMapped(request, requestToFailureCounterMapper)
        responseMonad.error(e)
    }
  }

  override def openWebsocket[T, WS_RESULT](
      request: Request[T, S],
      handler: NothingT[WS_RESULT]
  ): F[WebSocketResponse[WS_RESULT]] = handler // nothing is everything

  override def close(): F[Unit] = delegate.close()

  override def responseMonad: MonadError[F] = delegate.responseMonad

  private def incCounterIfMapped[T](
      request: Request[T, S],
      mapper: Request[_, S] => Option[CollectorNameWithLabels]
  ): Unit =
    mapper(request).foreach { data =>
      getOrCreateMetric(countersCache, data, createNewCounter).labels(data.labelValues: _*).inc()
    }

  private def getOrCreateMetric[T](
      cache: ConcurrentHashMap[String, T],
      data: CollectorNameWithLabels,
      create: CollectorNameWithLabels => T
  ): T =
    cache.computeIfAbsent(data.name, new java.util.function.Function[String, T] {
      override def apply(t: String): T = create(data)
    })

  private def createNewHistogram(data: CollectorNameWithLabels): Histogram =
    Histogram.build().name(data.name).labelNames(data.labelNames: _*).help(data.name).register(collectorRegistry)

  private def createNewGauge(data: CollectorNameWithLabels): Gauge =
    Gauge.build().name(data.name).labelNames(data.labelNames: _*).help(data.name).register(collectorRegistry)

  private def createNewCounter(data: CollectorNameWithLabels): Counter =
    Counter.build().name(data.name).labelNames(data.labelNames: _*).help(data.name).register(collectorRegistry)
}

object PrometheusBackend {
  val DefaultHistogramName = "sttp_request_latency"
  val DefaultRequestsInProgressGaugeName = "sttp_requests_in_progress"
  val DefaultSuccessCounterName = "sttp_requests_success_count"
  val DefaultErrorCounterName = "sttp_requests_error_count"
  val DefaultFailureCounterName = "sttp_requests_failure_count"

  def apply[F[_], S](
      delegate: SttpBackend[F, S, NothingT],
      requestToHistogramNameMapper: Request[_, S] => Option[CollectorNameWithLabels] = (_: Request[_, S]) =>
        Some(CollectorNameWithLabels(DefaultHistogramName)),
      requestToInProgressGaugeNameMapper: Request[_, S] => Option[CollectorNameWithLabels] = (_: Request[_, S]) =>
        Some(CollectorNameWithLabels(DefaultRequestsInProgressGaugeName)),
      requestToSuccessCounterMapper: Request[_, S] => Option[CollectorNameWithLabels] = (_: Request[_, S]) =>
        Some(CollectorNameWithLabels(DefaultSuccessCounterName)),
      requestToErrorCounterMapper: Request[_, S] => Option[CollectorNameWithLabels] = (_: Request[_, S]) =>
        Some(CollectorNameWithLabels(DefaultErrorCounterName)),
      requestToFailureCounterMapper: Request[_, S] => Option[CollectorNameWithLabels] = (_: Request[_, S]) =>
        Some(CollectorNameWithLabels(DefaultFailureCounterName)),
      collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry
  ): SttpBackend[F, S, NothingT] = {
    // redirects should be handled before prometheus
    new FollowRedirectsBackend[F, S, NothingT](
      new PrometheusBackend(
        delegate,
        requestToHistogramNameMapper,
        requestToInProgressGaugeNameMapper,
        requestToSuccessCounterMapper,
        requestToErrorCounterMapper,
        requestToFailureCounterMapper,
        collectorRegistry,
        cacheFor(histograms, collectorRegistry),
        cacheFor(gauges, collectorRegistry),
        cacheFor(counters, collectorRegistry)
      )
    )
  }

  /**
    * Clear cached collectors (gauges and histograms) both from the given collector registry, and from the backend.
    */
  @silent("discarded")
  def clear(collectorRegistry: CollectorRegistry): Unit = {
    collectorRegistry.clear()
    histograms.remove(collectorRegistry)
    gauges.remove(collectorRegistry)
    counters.remove(collectorRegistry)
  }

  /*
  Each collector can be registered in a collector registry only once - however there might be multiple backends registered
  with the same collector (trying to register a collector under the same name twice results in an exception).
  Hence, we need to store a global cache o created histograms/gauges, so that we can properly re-use them.
   */

  private val histograms = new mutable.WeakHashMap[CollectorRegistry, ConcurrentHashMap[String, Histogram]]
  private val gauges = new mutable.WeakHashMap[CollectorRegistry, ConcurrentHashMap[String, Gauge]]
  private val counters = new mutable.WeakHashMap[CollectorRegistry, ConcurrentHashMap[String, Counter]]

  private def cacheFor[T](
      cache: mutable.WeakHashMap[CollectorRegistry, ConcurrentHashMap[String, T]],
      collectorRegistry: CollectorRegistry
  ): ConcurrentHashMap[String, T] =
    cache.synchronized {
      cache.getOrElseUpdate(collectorRegistry, new ConcurrentHashMap[String, T]())
    }
}

/**
  * Represents the name of a collector, together with label names and values.
  * The same labels must be always returned, and in the same order.
  */
case class CollectorNameWithLabels(name: String, labels: List[(String, String)] = Nil) {
  def labelNames: Seq[String] = labels.map(_._1)
  def labelValues: Seq[String] = labels.map(_._2)
}
