package com.evolutiongaming.kafka.flow.journal

import cats.Monad
import cats.syntax.all.*
import com.evolutiongaming.catshelper.MeasureDuration
import com.evolutiongaming.kafka.flow.KafkaKey
import com.evolutiongaming.kafka.flow.metrics.MetricsOf
import com.evolutiongaming.kafka.flow.metrics.syntax.*
import com.evolutiongaming.skafka.consumer.ConsumerRecord
import com.evolutiongaming.smetrics.MetricsHelper.*
import com.evolutiongaming.smetrics.{LabelNames, Quantile, Quantiles}
import scodec.bits.ByteVector

object JournalDatabaseMetrics {

  def of[F[_]: Monad: MeasureDuration]: MetricsOf[F, JournalDatabase[F, KafkaKey, ConsumerRecord[String, ByteVector]]] =
    journalDatabaseMetricsOf

  implicit def journalDatabaseMetricsOf[F[_]: Monad: MeasureDuration]
    : MetricsOf[F, JournalDatabase[F, KafkaKey, ConsumerRecord[String, ByteVector]]] = { registry =>
    for {
      persistSummary <- registry.summary(
        name      = "journal_database_persist_duration_seconds",
        help      = "Time required to persist a single record to a database",
        quantiles = Quantiles(Quantile(0.9, 0.05), Quantile(0.99, 0.005)),
        labels    = LabelNames("topic", "partition")
      )
      getSummary <- registry.summary(
        name      = "journal_database_get_duration_seconds",
        help      = "Time required to get a whole journal from a database",
        quantiles = Quantiles(Quantile(0.9, 0.05), Quantile(0.99, 0.005)),
        labels    = LabelNames("topic", "partition")
      )
      deleteSummary <- registry.summary(
        name      = "journal_database_delete_duration_seconds",
        help      = "Time required to delete all journal records for the key from a database",
        quantiles = Quantiles(Quantile(0.9, 0.05), Quantile(0.99, 0.005)),
        labels    = LabelNames("topic", "partition")
      )
    } yield database =>
      new JournalDatabase[F, KafkaKey, ConsumerRecord[String, ByteVector]] {
        def persist(key: KafkaKey, journal: ConsumerRecord[String, ByteVector]) =
          database.persist(key, journal) measureDuration { duration =>
            persistSummary
              .labels(key.topicPartition.topic, key.topicPartition.partition.show)
              .observe(duration.toNanos.nanosToSeconds)
          }
        def get(key: KafkaKey) =
          database.get(key) measureTotalDuration { duration =>
            getSummary
              .labels(key.topicPartition.topic, key.topicPartition.partition.show)
              .observe(duration.toNanos.nanosToSeconds)
          }
        def delete(key: KafkaKey) =
          database.delete(key) measureDuration { duration =>
            deleteSummary
              .labels(key.topicPartition.topic, key.topicPartition.partition.show)
              .observe(duration.toNanos.nanosToSeconds)
          }

      }
  }

}
