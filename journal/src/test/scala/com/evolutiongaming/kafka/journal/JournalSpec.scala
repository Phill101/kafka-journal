package com.evolutiongaming.kafka.journal

import java.time.Instant

import com.evolutiongaming.concurrent.CurrentThreadExecutionContext
import com.evolutiongaming.kafka.journal.Alias._
import com.evolutiongaming.kafka.journal.FoldWhileHelper._
import com.evolutiongaming.kafka.journal.FutureHelper._
import com.evolutiongaming.kafka.journal.eventual.{EventualJournal, EventualRecord, PartitionOffset, TopicPointers}
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.safeakka.actor.ActorLog
import com.evolutiongaming.skafka.{Offset, Partition, Topic}
import org.scalatest.{Matchers, WordSpec}

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}

class JournalSpec extends WordSpec with Matchers {
  import JournalSpec._

  def test(journalOf: () => SeqNrJournal): Unit = {

    "append single event" in {
      val journal = journalOf()
      journal.read(SeqRange.All).get() shouldEqual Nil
      journal.append(1)
      journal.read(SeqRange.All).get() shouldEqual List(1)
    }

    "append many events atomically" in {
      val journal = journalOf()
      journal.append(1, 2, 3)
      journal.append(4, 5, 6)
      journal.read(SeqRange.All).get() shouldEqual List(1, 2, 3, 4, 5, 6)
    }

    "append many events one by one" in {
      val journal = journalOf()
      journal.append(1)
      journal.append(2)
      journal.append(3)
      journal.append(4)
      journal.read(SeqRange.All).get() shouldEqual List(1, 2, 3, 4)
    }

    "delete no events" in {
      val journal = journalOf()
      journal.delete(1).get()
      journal.read(SeqRange.All).get() shouldEqual Nil
      journal.lastSeqNr(SeqNr.Min).get() shouldEqual SeqNr.Min
    }

    "delete some events" in {
      val journal = journalOf()
      journal.append(1)
      journal.append(2, 3)
      journal.delete(2).get()
      journal.read(SeqRange.All).get() shouldEqual List(3)
      journal.lastSeqNr(SeqNr.Min).get() shouldEqual 3l
    }

    "delete all events" in {
      val journal = journalOf()
      journal.append(1)
      journal.append(2, 3)
      journal.delete(3).get()
      journal.read(SeqRange.All).get() shouldEqual Nil
      journal.lastSeqNr(SeqNr.Min).get() shouldEqual 3l
    }

    "delete existing events only" in {
      val journal = journalOf()
      journal.append(1)
      journal.append(2, 3)
      journal.delete(4).get()
      journal.read(SeqRange.All).get() shouldEqual Nil
      journal.lastSeqNr(SeqNr.Min).get() shouldEqual 3l
    }

    "delete 0 events" in {
      val journal = journalOf()
      journal.append(1).get()
      journal.delete(SeqNr.Min).get()
      journal.read(SeqRange.All).get() shouldEqual List(1)
      journal.lastSeqNr(SeqNr.Min).get() shouldEqual 1l
    }

    "delete max events" in {
      val journal = journalOf()
      journal.append(1)
      journal.append(2, 3)
      journal.delete(SeqNr.Max).get()
      journal.read(SeqRange.All).get() shouldEqual Nil
      journal.lastSeqNr(SeqNr.Min).get() shouldEqual 3l
    }

    "lastSeqNr" in {
      val journal = journalOf()
      journal.lastSeqNr(SeqNr.Min).get() shouldEqual SeqNr.Min
      journal.lastSeqNr(SeqNr.Max).get() shouldEqual SeqNr.Max
    }

    "read empty journal" in {
      val journal = journalOf()
      journal.read(SeqRange.All).get() shouldEqual Nil
    }

    "read all events" in {
      val journal = journalOf()
      journal.append(1l)
      journal.append(2l, 3l, 4l, 5l, 6l)
      journal.read(SeqRange.All).get() shouldEqual List(1l, 2l, 3l, 4l, 5l, 6l)
    }

    "read some events" in {
      val journal = journalOf()
      journal.append(1l, 2l)
      journal.append(3l, 4l, 5l, 6l)
      journal.read(2l __ 3l).get() shouldEqual List(2l, 3l)
      journal.read(5l __ 7l).get() shouldEqual List(5l, 6l)
    }

    "append, delete, append, delete, append, read, lastSeqNr" in {
      val journal = journalOf()
      journal.append(1l)
      journal.delete(3l)
      journal.append(2l, 3l)
      journal.delete(2l)
      journal.append(4l)
      journal.read(1l __ 2l).get() shouldEqual Nil
      journal.read(2l __ 3l).get() shouldEqual List(3l)
      journal.read(3l __ 4l).get() shouldEqual List(3l, 4l)
      journal.read(4l __ 5l).get() shouldEqual List(4l)
      journal.read(5l __ 6l).get() shouldEqual Nil
      journal.lastSeqNr(SeqNr.Min).get() shouldEqual 4l
    }
  }


  "Journal" when {

    "eventual journal is empty" should {

      test(() => journalOf())

      def journalOf() = {
        var actions: Queue[Action] = Queue.empty
        val eventualJournal = EventualJournal.Empty

        val withReadActions = WithReadActionsOneByOne(actions)

        val writeAction = new WriteAction {
          def apply(action: Action) = {
            actions = actions.enqueue(action)
            partition.future
          }
        }
        val journal = Journal(id, topic, ActorLog.empty, eventualJournal, withReadActions, writeAction)
        SeqNrJournal(journal)
      }
    }


    "kafka journal is empty" should {

      test(() => journalOf())

      def journalOf() = {
        var actions: Queue[Action] = Queue.empty
        var replicatedState = EventualJournalOf.State.Empty

        val eventualJournal = EventualJournalOf(replicatedState)

        val withReadActions = WithReadActionsOneByOne(actions.collect { case action: Action.Mark => action })

        val writeAction = new WriteAction {

          def apply(action: Action) = {
            actions = actions.enqueue(action)
            replicatedState = replicatedState(action, actions.size.toLong)
            partition.future
          }
        }

        val journal = Journal(id, topic, ActorLog.empty, eventualJournal, withReadActions, writeAction)
        SeqNrJournal(journal)
      }
    }


    "kafka and eventual journals are consistent" should {
      test(() => journalOf())

      def journalOf() = {
        var actions: Queue[Action] = Queue.empty
        var replicatedState = EventualJournalOf.State.Empty

        val eventualJournal = EventualJournalOf(replicatedState)

        val withReadActions = WithReadActionsOneByOne(actions)

        val writeAction = new WriteAction {

          def apply(action: Action) = {
            actions = actions.enqueue(action)
            replicatedState = replicatedState(action, actions.size.toLong)
            partition.future
          }
        }

        val journal = Journal(id, topic, ActorLog.empty, eventualJournal, withReadActions, writeAction)
        SeqNrJournal(journal)
      }
    }


    "eventual journal is one event behind the kafka journal" should {
      test(() => journalOf())

      def journalOf() = {
        var actions: Queue[Action] = Queue.empty
        var replicatedState = EventualJournalOf.State.Empty


        val eventualJournal = EventualJournalOf(replicatedState)

        val withReadActions = WithReadActionsOneByOne(actions)

        val writeAction = new WriteAction {

          def apply(action: Action) = {

            actions = actions.enqueue(action)

            for {
              actions <- actions.dropLast(1)
              action <- actions.lastOption
            } {
              val offset: Offset = actions.size.toLong - 1
              replicatedState = replicatedState(action, offset)
            }

            partition.future
          }
        }

        val journal = Journal(id, topic, ActorLog.empty, eventualJournal, withReadActions, writeAction)
        SeqNrJournal(journal)
      }
    }


    "eventual journal is two events behind the kafka journal" should {
      test(() => journalOf())

      def journalOf() = {
        var actions: Queue[Action] = Queue.empty
        var replicatedState = EventualJournalOf.State.Empty


        val eventualJournal = EventualJournalOf(replicatedState)

        val withReadActions = WithReadActionsOneByOne(actions)

        val writeAction = new WriteAction {

          def apply(action: Action) = {

            actions = actions.enqueue(action)

            for {
              actions <- actions.dropLast(2)
              action <- actions.lastOption
            } {
              val offset: Offset = actions.size.toLong - 1
              replicatedState = replicatedState(action, offset)
            }

            partition.future
          }
        }

        val journal = Journal(id, topic, ActorLog.empty, eventualJournal, withReadActions, writeAction)
        SeqNrJournal(journal)
      }
    }


    "eventual journal is three events behind the kafka journal" should {
      test(() => journalOf())

      def journalOf() = {
        var actions: Queue[Action] = Queue.empty
        var replicatedState = EventualJournalOf.State.Empty


        val eventualJournal = EventualJournalOf(replicatedState)

        val withReadActions = WithReadActionsOneByOne(actions)

        val writeAction = new WriteAction {

          def apply(action: Action) = {

            actions = actions.enqueue(action)

            for {
              actions <- actions.dropLast(3)
              action <- actions.lastOption
            } {
              val offset: Offset = actions.size.toLong - 1
              replicatedState = replicatedState(action, offset)
            }

            partition.future
          }
        }

        val journal = Journal(id, topic, ActorLog.empty, eventualJournal, withReadActions, writeAction)
        SeqNrJournal(journal)
      }
    }
  }
}

object JournalSpec {
  val id = "id"
  val topic = "topic"
  val timestamp = Instant.now()
  val partition: Partition = 0

  implicit val ec: ExecutionContext = CurrentThreadExecutionContext


  // TODO do we need Future in API ?
  trait SeqNrJournal {
    def append(seqNr: SeqNr, seqNrs: SeqNr*): Future[Unit]
    def read(range: SeqRange): Future[Seq[SeqNr]]

    // TODO not sure this should be a part of this API
    def lastSeqNr(from: SeqNr): Future[SeqNr]
    def delete(to: SeqNr): Future[Unit]
  }

  object SeqNrJournal {

    def apply(journal: Journal): SeqNrJournal = {

      new SeqNrJournal {

        def append(seqNr: SeqNr, seqNrs: SeqNr*) = {
          val events = for {seqNr <- Nel(seqNr, seqNrs: _*)} yield Event(seqNr)
          journal.append(events, timestamp)
        }

        def read(range: SeqRange) = {
          for {
            events <- journal.read(range)
          } yield for {
            event <- events
          } yield {
            event.seqNr
          }
        }

        def lastSeqNr(from: SeqNr) = journal.lastSeqNr(from)

        def delete(to: SeqNr) = journal.delete(to, timestamp)
      }
    }
  }


  object WithReadActionsOneByOne {
    def apply(actions: => Queue[Action]): WithReadActions = new WithReadActions {

      def apply[T](topic: Topic, partitionOffset: Option[PartitionOffset])(f: ReadActions => Future[T]) = {

        val readActions = new ReadActions {

          var left = actions

          def apply(id: Id): Future[Iterable[Action]] = {
            left.dequeueOption.fold(Future.nil[Action]) { case (action, left) =>
              this.left = left
              List(action).future
            }
          }
        }

        f(readActions)
      }
    }
  }


  object EventualJournalOf {

    def apply(state: => State): EventualJournal = {

      new EventualJournal {

        def topicPointers(topic: Topic) = {
          val pointers = Map(partition -> state.offset)
          TopicPointers(pointers).future
        }

        def read[S](id: Id, from: SeqNr, s: S)(f: FoldWhile[S, EventualRecord]) = {

          def read(state: State) = {
            state.events.foldWhile(s) { (s, replicated) =>
              val event = replicated.event
              if (event.seqNr >= from) {
                val record = EventualRecord(
                  id = id,
                  seqNr = event.seqNr,
                  timestamp = replicated.timestamp,
                  payload = event.payload,
                  tags = event.tags,
                  partitionOffset = replicated.partitionOffset)
                f(s, record)
              } else {
                (s, true)
              }
            }
          }

          read(state).future
        }

        def lastSeqNr(id: Id, from: SeqNr) = {

          def lastSeqNr(state: State) = {
            val seqNr = state.events.lastOption.fold(SeqNr.Min)(_.event.seqNr)
            val lastSeqNr = seqNr max state.deleteTo
            lastSeqNr.future
          }

          lastSeqNr(state)
        }
      }
    }


    case class State(
      events: Queue[ReplicatedEvent] = Queue.empty,
      deleteTo: SeqNr = SeqNr.Min,
      offset: Offset = 0l) {

      def apply(action: Action, offset: Offset): State = {

        def onAppend(action: Action.Append) = {
          val batch = for {
            event <- EventsSerializer.fromBytes(action.events)
          } yield {
            val partitionOffset = PartitionOffset(partition, offset)
            ReplicatedEvent(event, timestamp, partitionOffset)
          }
          copy(events = events.enqueue(batch.toList), offset = offset)
        }

        def onDelete(action: Action.Delete) = {
          def last = events.lastOption.fold(SeqNr.Min)(_.event.seqNr)

          val left = events.dropWhile(_.event.seqNr <= action.to)
          val deleteTo = left.headOption.fold(last)(_.event.seqNr.prev)
          copy(deleteTo = deleteTo, events = left, offset = offset)
        }

        action match {
          case action: Action.Append => onAppend(action)
          case action: Action.Delete => onDelete(action)
          case action: Action.Mark   => copy(offset = offset)
        }
      }
    }

    object State {
      val Empty: State = State()
    }
  }


  implicit class TestFutureOps[T](val self: Future[T]) extends AnyVal {
    def get(): T = self.value.get.get
  }

  implicit class QueueOps(val self: Queue[Action]) extends AnyVal {
    def fix: Queue[Action] = self.map {
      case action: Action.Append => action.copy(events = Bytes.Empty)
      case action: Action.Delete => action
      case action: Action.Mark   => action
    }

    def dropLast(n: Int): Option[Queue[Action]] = {
      if (self.size <= n) None
      else Some(self.dropRight(n))
    }
  }
}