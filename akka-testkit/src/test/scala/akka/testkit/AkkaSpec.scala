/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import language.{ postfixOps, reflectiveCalls }

import org.scalatest.{ WordSpec, BeforeAndAfterAll, Tag }
import org.scalatest.matchers.MustMatchers
import akka.actor.{ Actor, Props, ActorSystem, PoisonPill, DeadLetter, ActorSystemImpl }
import akka.event.{ Logging, LoggingAdapter }
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal
import com.typesafe.config.{ Config, ConfigFactory }
import java.io.PrintStream
import java.lang.management.{ ManagementFactory, ThreadInfo }
import java.util.Date
import java.util.concurrent.{ CountDownLatch, TimeoutException }
import akka.dispatch.Dispatchers
import akka.pattern.ask

object AkkaSpec {
  val testConf: Config = ConfigFactory.parseString("""
      akka {
        loggers = ["akka.testkit.TestEventListener"]
        loglevel = "WARNING"
        stdout-loglevel = "WARNING"
        actor {
          default-dispatcher {
            executor = "fork-join-executor"
            fork-join-executor {
              parallelism-min = 8
              parallelism-factor = 2.0
              parallelism-max = 8
            }
          }
        }
      }
                                                    """)

  def mapToConfig(map: Map[String, Any]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def getCallerName(clazz: Class[_]): String = {
    val s = Thread.currentThread.getStackTrace map (_.getClassName) drop 1 dropWhile (_ matches ".*AkkaSpec.?$")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }

}

abstract class AkkaSpec(_system: ActorSystem)
  extends TestKit(_system) with WordSpec with MustMatchers with BeforeAndAfterAll {

  def this(config: Config) = this(ActorSystem(AkkaSpec.getCallerName(getClass),
    ConfigFactory.load(config.withFallback(AkkaSpec.testConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(ActorSystem(AkkaSpec.getCallerName(getClass), AkkaSpec.testConf))

  val log: LoggingAdapter = Logging(system, this.getClass)

  private var testRunningLatch: Option[CountDownLatch] = None

  final override def beforeAll {
    startCoroner()
    atStartup()
  }

  final override def afterAll {
    beforeTermination()
    system.shutdown()
    try system.awaitTermination(5 seconds) catch {
      case _: TimeoutException ⇒
        system.log.warning("Failed to stop [{}] within 5 seconds", system.name)
        println(system.asInstanceOf[ActorSystemImpl].printTree)
    }
    afterTermination()
    stopCoroner()
  }

  protected def atStartup() {}

  protected def beforeTermination() {}

  protected def afterTermination() {}

  def spawn(dispatcherId: String = Dispatchers.DefaultDispatcherId)(body: ⇒ Unit): Unit =
    Future(body)(system.dispatchers.lookup(dispatcherId))

  private def startCoroner() {
    if (testRunningLatch.isDefined) throw new IllegalStateException("Test already running")
    testRunningLatch = Some(new CountDownLatch(1))
    val testClassName = getClass.getName
    val duration = expectedTestDuration.dilated
    val deadline = duration.fromNow
    val thread = new Thread(new Runnable {
      def run = await()

      @tailrec private def await() {
        val unlatched = try {
          testRunningLatch.get.await(deadline.timeLeft.length, deadline.timeLeft.unit)
        } catch {
          case _: InterruptedException ⇒ false
        }
        if (unlatched) {
          // Test completed; let the thread stop
        } else {
          if (deadline.timeLeft.length <= 0) {
            // Test took too long
            triggerCoronersReport()
          } else {
            await()
          }
        }
      }

      private def triggerCoronersReport() {
        val err = coronerOutput
        err.println(s"Coroner's Report for $testClassName")
        err.println(s"Test took longer than ${duration.toMillis}ms to finish. Looking for signs of foul play.")
        try {
          displayCoronersReport(err)
        } catch {
          case NonFatal(e) ⇒ {
            err.println("Error displaying Coroner's Report")
            e.printStackTrace(err)
          }
        }
      }

    }, s"Coroner-$testClassName")
    thread.start() // Must store thread in val to work around SI-7203
  }

  private def stopCoroner() {
    if (!testRunningLatch.isDefined) throw new IllegalStateException("Test not running")
    testRunningLatch.get.countDown() // Signal the test is over
    testRunningLatch = None
  }

  def coronerOutput: PrintStream = System.err

  def expectedTestDuration: FiniteDuration = 60 seconds

  def displayCoronersReport(err: PrintStream) {
    val osMx = ManagementFactory.getOperatingSystemMXBean()
    val rtMx = ManagementFactory.getRuntimeMXBean()
    val memMx = ManagementFactory.getMemoryMXBean()
    val threadMx = ManagementFactory.getThreadMXBean()

    import err.println
    println(s"OS Architecture: ${osMx.getArch()}")
    println(s"Available processors: ${osMx.getAvailableProcessors()}")
    println(s"System load (last minute): ${osMx.getSystemLoadAverage()}")
    println(s"VM start time: ${new Date(rtMx.getStartTime())}")
    println(s"VM uptime: ${rtMx.getUptime()}ms")
    println(s"Heap usage: ${memMx.getHeapMemoryUsage()}")
    println(s"Non-heap usage: ${memMx.getNonHeapMemoryUsage()}")

    def dumpAllThreads(): Seq[ThreadInfo] = {
      threadMx.dumpAllThreads(
        threadMx.isObjectMonitorUsageSupported(),
        threadMx.isSynchronizerUsageSupported())
    }

    def findDeadlockedThreads(): (Seq[ThreadInfo], String) = {
      val (ids, desc) = if (threadMx.isSynchronizerUsageSupported()) {
        (threadMx.findDeadlockedThreads(), "monitors and ownable synchronizers")
      } else {
        (threadMx.findMonitorDeadlockedThreads(), "monitors, but NOT ownable synchronizers")
      }
      if (ids == null) {
        (Seq.empty, desc)
      } else {
        val maxTraceDepth = 1000 // Seems deep enough
        (threadMx.getThreadInfo(ids, maxTraceDepth), desc)
      }
    }

    def printThreadInfo(threadInfos: Seq[ThreadInfo]) = {
      if (threadInfos.isEmpty) {
        println("None")
      } else {
        for (ti ← threadInfos.sortBy(_.getThreadName)) { println(ti) }
      }
    }

    println("All threads:")
    printThreadInfo(dumpAllThreads())

    val (deadlockedThreads, deadlockDesc) = findDeadlockedThreads()
    println(s"Deadlocks found for $deadlockDesc:")
    printThreadInfo(deadlockedThreads)
  }
}
