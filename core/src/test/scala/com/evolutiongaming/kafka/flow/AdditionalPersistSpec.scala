package com.evolutiongaming.kafka.flow

import cats.effect.testkit.TestControl
import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Ref, Resource}
import com.evolutiongaming.catshelper.{Log, LogOf}
import com.evolutiongaming.kafka.flow.effect.CatsEffectMtlInstances.*
import com.evolutiongaming.kafka.flow.kafka.ScheduleCommit
import com.evolutiongaming.kafka.flow.key.KeysOf
import com.evolutiongaming.kafka.flow.persistence.PersistenceOf
import com.evolutiongaming.kafka.flow.registry.EntityRegistry
import com.evolutiongaming.kafka.flow.snapshot.{SnapshotDatabase, SnapshotsOf}
import com.evolutiongaming.kafka.flow.timer.{TimerFlowOf, TimersOf}
import com.evolutiongaming.skafka.consumer.{ConsumerRecord, WithSize}
import com.evolutiongaming.skafka.{Offset, TopicPartition}
import munit.FunSuite
import scodec.bits.ByteVector

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

class AdditionalPersistSpec extends FunSuite {
  import AdditionalPersistSpec.TestFixture

  implicit val ioRuntime: IORuntime = IORuntime.global

  test("persist state both on request and periodically when regular persist succeeds") {
    val fold: EnhancedFold[IO, String, ConsumerRecord[String, ByteVector]] =
      EnhancedFold.of[IO, String, ConsumerRecord[String, ByteVector]] { (extras, _, record) =>
        val value = new String(record.value.get.value.toArray, StandardCharsets.UTF_8)
        val key   = record.key.get.value

        for {
          _ <- key match {
            case "key1" if value == "value2" || value == "value8"  => extras.requestAdditionalPersist
            case "key2" if value == "value4" || value == "value10" => extras.requestAdditionalPersist
            case _                                                 => IO.unit
          }
        } yield Some(value)
      }

    val fixture = new TestFixture {
      override val enhancedFold: EnhancedFold[IO, String, ConsumerRecord[String, ByteVector]] = fold
    }

    // Additional persist should happen at key1:value2 and key2:value4
    val firstBatch = fixture.batch("key1", 1, 3) ++ fixture.batch("key2", 4, 6)
    // Additional persist should happen at key1:value8 and key2:value10
    val secondBatch = fixture.batch("key1", 7, 9) ++ fixture.batch("key2", 10, 12)
    // Regular persist should happen at key1:value14 and key2:value16
    val thirdBatch = fixture.batch("key1", 13, 14) ++ fixture.batch("key2", 15, 16)

    val program = fixture.partitionFlow.use { partitionFlow =>
      // we add a small sleep in the beginning to move clock a bit otherwise commitOffsetsAt will have the same
      // 0 value as initial clock which won't happen in real life
      for {
        _ <- IO.sleep(1.second)
        _ <- partitionFlow(firstBatch)
        _ <- IO.sleep(30.seconds)
        _ <- partitionFlow(secondBatch)
        _ <- IO.sleep(1.minute)
        _ <- partitionFlow(thirdBatch)
      } yield ()
    }

    val testIO = TestControl.execute(program).flatMap { control =>
      // ensure initial state is empty
      assertEquals(fixture.snapshots.get.unsafeRunSync(), Map.empty[KafkaKey, String])
      assertEquals(fixture.commits.get.unsafeRunSync(), List.empty)

      for {
        _ <- control.tick
        // T=1, the first batch is handled, key1:value2 and key2:value1 are persisted additionally on request.
        // The offset is committed because:
        //   1) it's the first time after PartitionFlow is created and current clock is after "commitOffsetsAt"
        //      in PartitionFlow in this single case
        //   2) key1 and key2 are holding new offsets after additional persisting
        // Offset 103 is committed as it's the minimal held offset among all keys (offset of key1:value2 + 1)
        _ <- control.advanceAndTick(1.second)
        _ <- fixture
          .snapshots
          .get
          .map(
            assertEquals(
              _,
              Map(
                KafkaKey("app", "group", TopicPartition.empty, "key1") -> "value2",
                KafkaKey("app", "group", TopicPartition.empty, "key2") -> "value4"
              )
            )
          )
        _ <- fixture.commits.get.map(assertEquals(_, List(Offset.unsafe(103L))))
        // T=31, the second batch is handled, key1:value8 and key2:value10 are persisted additionally after cooldown expired.
        // The offset is not committed as it's not yet time for a regular commit
        _ <- control.advanceAndTick(30.seconds)
        _ <- fixture
          .snapshots
          .get
          .map(
            assertEquals(
              _,
              Map(
                KafkaKey("app", "group", TopicPartition.empty, "key1") -> "value8",
                KafkaKey("app", "group", TopicPartition.empty, "key2") -> "value10"
              )
            )
          )
        _ <- fixture.commits.get.map(assertEquals(_, List(Offset.unsafe(103L))))
        // T=91, the third batch is handled, no additional persist requested, but the latest state is persisted
        // on a regular basis (key1:value14, key2:value16) and latest offset (117) is committed
        _ <- control.advanceAndTick(1.minute)
        _ <- fixture
          .snapshots
          .get
          .map(
            assertEquals(
              _,
              Map(
                KafkaKey("app", "group", TopicPartition.empty, "key1") -> "value14",
                KafkaKey("app", "group", TopicPartition.empty, "key2") -> "value16"
              )
            )
          )
        _ <- fixture.commits.get.map(assertEquals(_, List(Offset.unsafe(103L), Offset.unsafe(117L))))
      } yield ()
    }

    testIO.unsafeRunSync()
  }

  test("persist state both on request and periodically when persisting results in error") {
    val fold: EnhancedFold[IO, String, ConsumerRecord[String, ByteVector]] =
      EnhancedFold.of[IO, String, ConsumerRecord[String, ByteVector]] { (extras, _, record) =>
        val value = new String(record.value.get.value.toArray, StandardCharsets.UTF_8)
        val key   = record.key.get.value

        for {
          _ <- key match {
            case "key1" if value == "value7" => extras.requestAdditionalPersist
            case _                           => IO.unit
          }
        } yield Some(value)
      }

    val fixture = new TestFixture {
      override val enhancedFold: EnhancedFold[IO, String, ConsumerRecord[String, ByteVector]] = fold
      override val ignorePersistFailures: Boolean                                             = true

      override def snapshotDatabase: SnapshotDatabase[IO, KafkaKey, String] =
        new SnapshotDatabase[IO, KafkaKey, String] {
          override def delete(key: KafkaKey): IO[Unit]        = snapshots.update(_ - key)
          override def get(key: KafkaKey): IO[Option[String]] = snapshots.get.map(_.get(key))
          override def persist(key: KafkaKey, snapshot: String): IO[Unit] =
            if (key.key == "key1" && snapshot == "value10") {
              IO.raiseError(new Exception("Persist error"))
            } else snapshots.update(_ + (key -> snapshot))
        }
    }

    // First batch doesn't trigger persisting at all
    val firstBatch = List(fixture.record("key1", 1), fixture.record("key2", 2), fixture.record("key3", 3))
    // Second batch triggers regular persisting by timer for key1:value4, key2:value5, key3:value6
    val secondBatch = List(fixture.record("key1", 4), fixture.record("key2", 5), fixture.record("key3", 6))
    // Third batch triggers additional persisting for key1:value7 only, so it should arrive between regular persists
    val thirdBatch = List(fixture.record("key1", 7), fixture.record("key2", 8), fixture.record("key3", 9))
    // Fourth batch triggers regular persisting by timer for all keys: key1:value10, key2:value11, key3:value12
    val fourthBatch = List(fixture.record("key1", 10), fixture.record("key2", 11), fixture.record("key3", 12))

    val program = fixture.partitionFlow.use { partitionFlow =>
      // we add a small sleep in the beginning to move clock a bit otherwise commitOffsetsAt will have the same
      // 0 value as initial clock which won't happen in real life
      for {
        _ <- IO.sleep(1.second)
        _ <- partitionFlow(firstBatch)
        _ <- IO.sleep(60.seconds)
        _ <- partitionFlow(secondBatch)
        _ <- IO.sleep(5.seconds)
        _ <- partitionFlow(thirdBatch)
        _ <- IO.sleep(60.seconds)
        _ <- partitionFlow(fourthBatch)
      } yield ()
    }

    val testIO = TestControl.execute(program).flatMap { control =>
      // ensure initial state is empty
      assertEquals(fixture.snapshots.get.unsafeRunSync(), Map.empty[KafkaKey, String])
      assertEquals(fixture.commits.get.unsafeRunSync(), List.empty)

      for {
        _ <- control.tick
        // T=1, the first batch is handled, nothing is persisted, offset 101 of key:value1 is committed
        // as it's the lowest one and all keys were just seen the first time
        _ <- control.advanceAndTick(1.second)
        _ <- fixture.snapshots.get.map(assertEquals(_, Map.empty[KafkaKey, String]))
        _ <- fixture.commits.get.map(assertEquals(_, List(Offset.unsafe(101L))))
        // T=61, the second batch is handled, key1:value4, key2:value5, key3:value6 are persisted on a regular basis.
        // Offset 107 is committed as the next one after key3:value6
        _ <- control.advanceAndTick(60.seconds)
        _ <- fixture
          .snapshots
          .get
          .map(
            assertEquals(
              _,
              Map(
                KafkaKey("app", "group", TopicPartition.empty, "key1") -> "value4",
                KafkaKey("app", "group", TopicPartition.empty, "key2") -> "value5",
                KafkaKey("app", "group", TopicPartition.empty, "key3") -> "value6"
              )
            )
          )
        _ <- fixture.commits.get.map(assertEquals(_, List(Offset.unsafe(101L), Offset.unsafe(107L))))
        // T=66, the third batch is handled, key1:value7 is persisted additionally; no new offset committed
        _ <- control.advanceAndTick(5.seconds)
        _ <- fixture
          .snapshots
          .get
          .map(
            assertEquals(
              _,
              Map(
                KafkaKey("app", "group", TopicPartition.empty, "key1") -> "value7",
                KafkaKey("app", "group", TopicPartition.empty, "key2") -> "value5",
                KafkaKey("app", "group", TopicPartition.empty, "key3") -> "value6"
              )
            )
          )
        _ <- fixture.commits.get.map(assertEquals(_, List(Offset.unsafe(101L), Offset.unsafe(107L))))
        // T=126, the fourth batch is handled
        // key1:value10 is persisted unsuccessfully, the error is ignored; key2:value11 and key3:value12 are persisted successfully.
        // Offset 108 is committed as the next one after additionally persisted key1:value7 from the previous batch
        _ <- control.advanceAndTick(60.seconds)
        _ <- fixture
          .snapshots
          .get
          .map(
            assertEquals(
              _,
              Map(
                KafkaKey("app", "group", TopicPartition.empty, "key1") -> "value7",
                KafkaKey("app", "group", TopicPartition.empty, "key2") -> "value11",
                KafkaKey("app", "group", TopicPartition.empty, "key3") -> "value12"
              )
            )
          )
        _ <- fixture
          .commits
          .get
          .map(assertEquals(_, List(Offset.unsafe(101L), Offset.unsafe(107L), Offset.unsafe(108L))))
      } yield ()
    }

    testIO.unsafeRunSync()
  }

}

object AdditionalPersistSpec {
  class TestFixture {
    implicit val logOf: LogOf[IO] = LogOf.empty[IO]
    implicit val log: Log[IO]     = logOf.apply(classOf[AdditionalPersistSpec]).unsafeRunSync()(IORuntime.global)

    val snapshots: Ref[IO, Map[KafkaKey, String]]                = Ref.unsafe[IO, Map[KafkaKey, String]](Map.empty)
    def snapshotDatabase: SnapshotDatabase[IO, KafkaKey, String] = SnapshotDatabase.memory(snapshots.stateInstance)

    val commits = Ref.unsafe[IO, List[Offset]](List.empty)
    val scheduleCommit = new ScheduleCommit[IO] {
      override def schedule(offset: Offset): IO[Unit] = commits.update(_ :+ offset)
    }

    def ignorePersistFailures: Boolean = false

    def enhancedFold: EnhancedFold[IO, String, ConsumerRecord[String, ByteVector]] =
      EnhancedFold.of[IO, String, ConsumerRecord[String, ByteVector]] { (extras, _, record) =>
        val value = new String(record.value.get.value.toArray, StandardCharsets.UTF_8)
        val key   = record.key.get.value

        for {
          _ <- key match {
            case "key1" if value == "value2" || value == "value8"  => extras.requestAdditionalPersist
            case "key2" if value == "value4" || value == "value10" => extras.requestAdditionalPersist
            case _                                                 => IO.unit
          }
        } yield Some(value)
      }

    def record(key: String, i: Int): ConsumerRecord[String, ByteVector] =
      ConsumerRecord[String, ByteVector](
        topicPartition   = TopicPartition.empty,
        offset           = Offset.unsafe(100L + i.toLong),
        timestampAndType = None,
        key              = Some(WithSize(key)),
        value            = Some(WithSize(ByteVector(s"value$i".getBytes(StandardCharsets.UTF_8)))),
        headers          = List.empty
      )

    def batch(key: String, from: Int, to: Int): List[ConsumerRecord[String, ByteVector]] =
      (from to to).map(i => record(key, i)).toList

    def partitionFlow: Resource[IO, PartitionFlow[IO]] =
      for {
        keysOf   <- Resource.eval(KeysOf.memory1[IO, KafkaKey])
        timersOf <- Resource.eval(TimersOf.memory[IO, KafkaKey])
        partitionFlow <- PartitionFlow.resource(
          topicPartition = TopicPartition.empty,
          assignedAt     = Offset.unsafe(100L),
          keyStateOf = KeyStateOf.eagerRecovery(
            applicationId = "app",
            groupId       = "group",
            keysOf        = keysOf,
            timersOf      = timersOf,
            persistenceOf = PersistenceOf
              .snapshotsOnly(keysOf, SnapshotsOf.backedBy(snapshotDatabase)),
            keyFlowOf = KeyFlowOf(
              timerFlowOf = TimerFlowOf.persistPeriodically[IO](
                fireEvery           = 1.minute,
                persistEvery        = 1.minute,
                ignorePersistErrors = ignorePersistFailures
              ),
              fold = enhancedFold,
              tick = TickOption.id[IO, String],
            ),
            additionalPersistOf = AdditionalStatePersistOf.of[IO, String](cooldown = 20.seconds),
            registry            = EntityRegistry.empty[IO, KafkaKey, String]
          ),
          config         = PartitionFlowConfig(commitOffsetsInterval = 1.minute),
          scheduleCommit = scheduleCommit
        )
      } yield partitionFlow
  }
}
