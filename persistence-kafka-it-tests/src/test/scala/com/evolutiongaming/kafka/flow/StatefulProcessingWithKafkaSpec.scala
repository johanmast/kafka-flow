package com.evolutiongaming.kafka.flow

import cats.ApplicativeThrow
import cats.data.NonEmptyList
import cats.effect.unsafe.IORuntime
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.evolution.playjson.jsoniter.PlayJsonJsoniter
import com.evolutiongaming.catshelper.{Log, LogOf}
import com.evolutiongaming.kafka.flow.StatefulProcessingWithKafkaSpec.*
import com.evolutiongaming.kafka.flow.kafka.KafkaModule
import com.evolutiongaming.kafka.flow.kafkapersistence.{
  KafkaPersistenceModule,
  KafkaPersistenceModuleOf,
  kafkaEagerRecovery
}
import com.evolutiongaming.kafka.flow.key.KeysOf
import com.evolutiongaming.kafka.flow.persistence.{PersistenceOf, SnapshotPersistenceOf}
import com.evolutiongaming.kafka.flow.registry.EntityRegistry
import com.evolutiongaming.kafka.flow.snapshot.{SnapshotDatabase, SnapshotsOf}
import com.evolutiongaming.kafka.flow.timer.{TimerFlowOf, TimersOf}
import com.evolutiongaming.retry.Retry
import com.evolutiongaming.skafka.consumer.{AutoOffsetReset, ConsumerConfig, ConsumerOf, ConsumerRecord}
import com.evolutiongaming.skafka.producer.{ProducerConfig, ProducerOf, ProducerRecord, RecordMetadata}
import com.evolutiongaming.skafka.{Bytes, CommonConfig, Partition}
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, NewTopic}
import play.api.libs.json.{JsResultException, Json, OFormat}
import scodec.bits.ByteVector

import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/*
 Using embedded/real kafka:
  0. produce some input kafka messages to be processed
  1. Start streaming app, process the input, shut down the app so it would persist the state and commit offsets on shutdown
  2. produce more input kafka messages to be processed
  3. start streaming app again, it should recover persisted state and continue processing

  verification can be implemented by testing against output kafka messages
  or
  Ref/StateT/etc injected in KeyFlow/PartitionFlow/etc (don't know yet how exactly)

  As a side effect of this spec we should get an attempt at a more user friendlier API
  for building simple stateful processing app with kafka

 */
class StatefulProcessingWithKafkaSpec extends ForAllKafkaSuite {
  implicit val ioRuntime: IORuntime = IORuntime.global
  implicit val logOf: LogOf[IO]     = LogOf.slf4j[IO].unsafeRunSync()
  implicit val log: Log[IO]         = logOf(this.getClass).unsafeRunSync()

  private def producerConfig =
    ProducerConfig(common = CommonConfig(bootstrapServers = NonEmptyList.one(kafka.container.bootstrapServers)))

  trait Persistence {
    def keysOf: KeysOf[IO, KafkaKey]
    def snapshotPersistenceOf: SnapshotPersistenceOf[IO, KafkaKey, State, ConsumerRecord[String, ByteVector]]
    def reset: IO[Unit]
  }

  private val inMemoryPersistenceModule: KafkaPersistenceModule[IO, State] = new KafkaPersistenceModule[IO, State] {
    private val db: SnapshotDatabase[IO, KafkaKey, State] = SnapshotDatabase.memory[IO, KafkaKey, State].unsafeRunSync()
    private val snapshotsOf: SnapshotsOf[IO, KafkaKey, State] = SnapshotsOf.backedBy(db)
    val snapshotPersistenceOf: SnapshotPersistenceOf[IO, KafkaKey, State, ConsumerRecord[String, ByteVector]] =
      PersistenceOf.snapshotsOnly(keysOf, snapshotsOf)

    override def keysOf: KeysOf[IO, KafkaKey] = KeysOf.memory1[IO, KafkaKey].unsafeRunSync()
    override def persistenceOf: SnapshotPersistenceOf[IO, KafkaKey, State, ConsumerRecord[String, ByteVector]] =
      snapshotPersistenceOf
  }

  private val inMemoryPersistenceModuleOf: KafkaPersistenceModuleOf[IO, State] =
    new KafkaPersistenceModuleOf[IO, State] {
      override def make(partition: Partition): Resource[IO, KafkaPersistenceModule[IO, State]] =
        Resource.pure(inMemoryPersistenceModule)
    }

  private val appId       = "app-id"
  private val testGroupId = "group-id"
  /*
   * State stores:
   *
   * one aspect is:
   *  - lazy loading
   * and/or
   *  - eager loading
   *
   * and another one is:
   *  - state snapshot
   * and/or
   *  - messages/events leading/recovering to/the state
   *
   * eager loading is almost the same as lazy one:
   * - just with warm up as a first step before accessing any key for the very first time
   * - and resources release when warm up is done (read compact kafka topic, fold by key, convert to case classes using FromBytes, release=throw away loaded bytes Map[Key, Bytes])
   * - oh actually it's not a Resource.release, as we're using the state store within scope of the Resource
   */
  private def kafkaPersistenceModuleOf: Resource[IO, KafkaPersistenceModuleOf[IO, State]] = {
    val stateTopic = "state-topic-StatefulProcessingWithKafkaSpec"

    ProducerOf
      .apply1[IO]()
      .apply(producerConfig)
      .map { producer =>
        KafkaPersistenceModuleOf.caching[IO, State](
          consumerOf = ConsumerOf.apply1[IO](),
          producer   = producer,
          consumerConfig = ConsumerConfig(
            common          = producerConfig.common,
            autoCommit      = false,
            autoOffsetReset = AutoOffsetReset.Earliest
          ),
          snapshotTopic = stateTopic
        )
      }
      .evalTap(_ => createTopic(stateTopic, 2))
  }

  test("stateful processing using in-memory persistence") {
    // using unique input topic name per test as weaver is running tests in parallel
    val inputTopic          = "in-memory-persistence-test"
    val persistenceModuleOf = inMemoryPersistenceModuleOf
    comboTestCase(kafkaModule(), persistenceModuleOf, inputTopic).unsafeRunSync()
  }

  test("stateful processing using kafka persistence") {
    // using unique input topic name per test as weaver is running tests in parallel
    val inputTopic = "kafka-persistence-test"
    kafkaPersistenceModuleOf
      .use(module => comboTestCase(kafkaModule(), module, inputTopic))
      .unsafeRunSync()
  }

  private def createTopic(topic: String, partitions: Int) = {
    val props = new Properties
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.container.bootstrapServers)

    Resource.make(IO.delay(AdminClient.create(props)))(cl => IO(cl.close())).use { client =>
      IO(client.createTopics(List(new NewTopic(topic, partitions, 1.toShort)).asJava)).map(res =>
        res.all().get(10, TimeUnit.SECONDS)
      )
    }
  }

  private def comboTestCase(
    kafka: KafkaModule[IO],
    persistenceModuleOf: KafkaPersistenceModuleOf[IO, State],
    inputTopic: String
  ): IO[Unit] = {
    for {
      _ <- createTopic(inputTopic, 2)
      _ <- testCase(kafka, persistenceModuleOf, inputTopic, Partition.unsafe(0), "key0")
      _ <- testCase(kafka, persistenceModuleOf, inputTopic, Partition.unsafe(1), "key1")
    } yield ()
  }

  private def testCase(
    kafka: KafkaModule[IO],
    persistenceModuleOf: KafkaPersistenceModuleOf[IO, State],
    inputTopic: String,
    partition: Partition,
    key: String
  ): IO[Unit] = {
    implicit val retry = Retry.empty[IO]

    def produceInput(value: Int): IO[RecordMetadata] = {
      val config = producerConfig.copy(common =
        producerConfig.common.copy(clientId = Some("StatefulProcessingWithKafkaSpec-producer"))
      )
      kafka.producerOf(config) use { producer =>
        val record = ProducerRecord[String, String](inputTopic, value.toString.some, key.some, partition.some)
        producer.send(record).flatten
      }
    }

    def program: IO[List[Output]] = for {
      output   <- Ref.of[IO, List[Output]](List.empty)
      finished <- Deferred[IO, Unit]
      flowOf   <- topicFlowOf(persistenceModuleOf, output, finished)
      run = KafkaFlow.resource(
        consumer = kafka.consumerOf("groupId-StatefulProcessingWithKafkaSpec"),
        flowOf = ConsumerFlowOf[IO](
          topic  = inputTopic,
          flowOf = flowOf
        )
      )
      // wait for records to be processed
      _      <- run.use(_ => finished.get.timeout(5.seconds))
      output <- output.get
    } yield output

    for {
      _      <- produceInput(1)
      _      <- produceInput(2)
      _      <- produceInput(3)
      output <- program
      _ = assertEquals(
        output,
        List(
          // 1st run of the program, starting with empty state and no committed offsets
          // so that we read topic from the beginning
          Output(key, None, Input(1)),
          Output(key, State(1).some, Input(2)),
          Output(key, State(2).some, Input(3))
        )
      )

      _      <- produceInput(4)
      _      <- produceInput(5)
      _      <- produceInput(6)
      output <- program
      _ = assertEquals(
        output,
        List(
          // 1st run finished and should have persisted State(3) and committed offsets
          // so that we continue from Input(4)

          // 2nd run of the program:
          // recovered State(3) from persistence
          Output(key, State(3).some, Input(4)),
          Output(key, State(4).some, Input(5)),
          Output(key, State(5).some, Input(6))
        )
      )

      _      <- produceInput(0) // Input(0) removes state
      output <- program
      _ = assertEquals(
        output,
        List(
          // recovered State(6) from persistence, proving that we can overwrite existing state
          Output(key, State(6).some, Input(0))
        )
      )

      _      <- produceInput(9)
      output <- program
      _ = assertEquals(
        output,
        List(
          // 4th run of the program started with empty state, proving that we can remove state from persistence
          Output(key, none, Input(9))
        )
      )
    } yield ()
  }

  def topicFlowOf(
    persistenceModuleOf: KafkaPersistenceModuleOf[IO, State],
    output: Ref[IO, List[Output]],
    finished: Deferred[IO, Unit]
  ): IO[TopicFlowOf[IO]] = {
    for {
      timersOf <- TimersOf.memory[IO, KafkaKey]
      partitionFlowOf = kafkaEagerRecovery[IO, State](
        kafkaPersistenceModuleOf = persistenceModuleOf,
        applicationId            = appId,
        groupId                  = testGroupId,
        timersOf                 = timersOf,
        timerFlowOf = TimerFlowOf
          .persistPeriodically[IO](
            // 0 seconds intervals are used to persist state after every consumer.poll
            // to simplify test scenarios
            fireEvery    = 0.seconds,
            persistEvery = 0.seconds,
            // flush on revoke is set to false, as it has no impact on test outcomes
            // coz we persist the state after every consumer.poll
            flushOnRevoke = false
          ),
        fold = bizLogic(output, finished, Log[IO]),
        partitionFlowConfig = PartitionFlowConfig(
          // 0 seconds intervals are used to commit offsets after every consumer.poll
          // to simplify test scenarios
          triggerTimersInterval = 0.seconds,
          commitOffsetsInterval = 0.seconds
        ),
        tick     = TickOption.id[IO, State],
        filter   = none,
        registry = EntityRegistry.empty[IO, KafkaKey, State]
      )
    } yield TopicFlowOf(partitionFlowOf)
  }

}

object StatefulProcessingWithKafkaSpec {

  /*
     Minimal set of configurations/descriptors for stateful processing with Kafka:
     - Aggregate state S for given key K by consuming events/messages E/A (Output) (can be done with kafka-flow's Fold)
     - Fold - execute some side effect after processing of E/A (in our case producing kafka message )
     - periodically (e.g. once in 10 seconds) commit offsets after S is persisted and IFF output messages are acked by Kafka broker
     serdes:
     - read input E
     - read/write state S
     - write output O
     defaults:
     - reliable kafka producer with acks=all, idempotence=true
   */

  final case class Input(n: Int)

  final case class State(n: Int)
  object State {
    implicit val StateFormat: OFormat[State] = Json.format[State]

    // Implementations for both toBytes and fromBytes are copied from kafka-journal by inlining some intermediate KJ's
    // typeclass implementations to make the test closer to how it's going to be used in production.
    implicit def fromBytes[F[_]: ApplicativeThrow]: com.evolutiongaming.skafka.FromBytes[F, State] =
      (bytes: Bytes, _) => {
        val toJsValue = PlayJsonJsoniter.deserialize(bytes).handleErrorWith {
          case e =>
            Try(Json.parse(bytes)).adaptErr { _ =>
              val str =
                ByteVector.view(bytes).decodeString(StandardCharsets.UTF_8).getOrElse(new String(bytes))
              new RuntimeException(s"Failed to parse $str: $e", e)
            }
        }

        ApplicativeThrow[F].fromTry(
          toJsValue.flatMap(jsValue =>
            StateFormat
              .reads(jsValue)
              .fold(
                err => Failure(new RuntimeException(s"FromJsResult failed: $err", JsResultException(err))),
                Success(_)
              )
          )
        )
      }

    implicit def toBytes[F[_]: ApplicativeThrow]: com.evolutiongaming.skafka.ToBytes[F, State] = (state, _) => {
      val jsValue = StateFormat.writes(state)
      val bytes = Try(PlayJsonJsoniter.serialize(jsValue)).handleErrorWith { e =>
        Try(Json.toBytes(jsValue)).adaptErr { _ =>
          new RuntimeException(s"ToJsResult failed for $state: $e", e)
        }
      }

      ApplicativeThrow[F].fromTry(bytes)
    }
  }

  final case class Output(key: String, stateBeforeInput: Option[State], input: Input)

  /** processing kafka messages Input(n: Int) by storing `n` in State(n: Int) key is considered processed when n == 0
    * (i.e. state can be removed from persistent storage)
    * @param output
    *   list of Output(stateBeforeInput: Option[State], input: Input)
    * @param finished
    *   is completed on Input(n % 3 == 0 || n == 0)
    */
  def bizLogic(
    output: Ref[IO, List[Output]],
    finished: Deferred[IO, Unit],
    log: Log[IO]
  ): FoldOption[IO, State, ConsumerRecord[String, ByteVector]] =
    FoldOption.of { (state, record) =>
      var isFinished = false
      for {
        // parse input assuming correct payload, otherwise intentionally exploding with exception to have simpler test
        input <- IO(Input(record.value.get.value.decodeUtf8.toOption.map(_.toInt).get))
        key    = record.key.get.value
        newState =
          if (input.n == 0) none
          else state.fold(State(input.n))(s => s.copy(n = input.n)).some
        _ <- output.update(_ :+ Output(key, state, input))
        _ <- if (input.n == 0 || input.n % 3 == 0) finished.complete(()) <* IO({ isFinished = true }) else IO.unit
        _ <- log.info(s"bizLogic: $key - $input, old state: $state, new state: $newState, finished: $isFinished")
      } yield newState
    }

}
