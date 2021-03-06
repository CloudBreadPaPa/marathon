package mesosphere.marathon
package core.instance

import mesosphere.UnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.state.PathId._

import scala.concurrent.duration._

class InstanceStateTest extends UnitTest {

  "The InstanceState factory" when {
    "passed running instances" should {
      val f = new Fixture

      val startTimestamps = Seq(Some(f.clock.now), Some(f.clock.now - 1.hour))
      val tasks: Map[Task.Id, Task] = f.tasks(Condition.Running, Condition.Running)
        .values
        .zip(startTimestamps)
        .map {
          case (task, startTime) =>
            val ephemeralTask = task.asInstanceOf[Task.LaunchedEphemeral]
            val newStatus: Task.Status = ephemeralTask.status.copy(startedAt = startTime)
            task.taskId -> ephemeralTask.copy(status = newStatus)
        }(collection.breakOut)

      val state = Instance.InstanceState(None, tasks, f.clock.now())

      "set the oldest task timestamp as the activeSince timestamp" in { state.activeSince should be(Some(f.clock.now - 1.hour)) }
      "set the instance condition to running" in { state.condition should be(Condition.Running) }
    }

    "passed only staging tasks" should {
      val f = new Fixture

      val tasks: Map[Task.Id, Task] = f.tasks(Condition.Staging, Condition.Staging)
      val state = Instance.InstanceState(None, tasks, f.clock.now())

      "not set the activeSince timestamp" in { state.activeSince should not be 'defined }
      "set the instance condition to staging" in { state.condition should be(Condition.Staging) }
    }

    "passed one running task and one staging" should {
      val f = new Fixture

      val startTimestamps = Seq(Some(f.clock.now - 1.hour), None)
      val tasks: Map[Task.Id, Task] = f.tasks(Condition.Running, Condition.Staging)
        .values
        .zip(startTimestamps)
        .map {
          case (task, startTime) =>
            val ephemeralTask = task.asInstanceOf[Task.LaunchedEphemeral]
            val newStatus: Task.Status = ephemeralTask.status.copy(startedAt = startTime)
            task.taskId -> ephemeralTask.copy(status = newStatus)
        }(collection.breakOut)

      val state = Instance.InstanceState(None, tasks, f.clock.now())

      "set the activeSince timestamp to the one from running" in { state.activeSince should be(Some(f.clock.now - 1.hour)) }
      "set the instance condition to staging" in { state.condition should be(Condition.Staging) }
    }

  }

  class Fixture {
    val id = "/test".toPath
    val clock = ConstantClock()

    val agentInfo = Instance.AgentInfo("", None, Nil)

    def tasks(statuses: Condition*): Map[Task.Id, Task] = tasks(statuses.to[Seq])

    def tasks(statuses: Seq[Condition]): Map[Task.Id, Task] =
      statuses.map { status =>
        val task = TestTaskBuilder.Helper.minimalTask(Task.Id.forRunSpec(id), Timestamp.now(), None, status)
        task.taskId -> task
      }(collection.breakOut)
  }
}
