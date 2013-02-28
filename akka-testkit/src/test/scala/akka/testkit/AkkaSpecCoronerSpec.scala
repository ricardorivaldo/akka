/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import java.io._
import java.util.concurrent._
import org.scalatest.matchers.MustMatchers
import scala.concurrent.duration._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AkkaSpecCoronerSpec extends AkkaSpec with MustMatchers {

  val coronerOutputBytes = new ByteArrayOutputStream()

  override def coronerOutput: PrintStream = new PrintStream(coronerOutputBytes)

  override def expectedTestDuration: FiniteDuration = 1.seconds

  def captureCoronersReport(body: ⇒ Unit): String = {
    coronerOutputBytes.reset()
    body
    new String(coronerOutputBytes.toByteArray())
  }

  "An AkkaSpec" must {

    "display deadlock information" in {
      val report = captureCoronersReport {

        // Create two threads that each recursively synchronize on a list of
        // objects. Give each thread the same objects, but in reversed order.
        // Control execution of the threads so that they each hold an object
        // that the other wants to synchronize on. BOOM! Deadlock. Wait a bit
        // and give the Coroner a chance to produce its report, then interrupt
        // the results and check the report contains the deadlock info.

        case class SyncThread(name: String, thread: Thread, ready: Semaphore, proceed: Semaphore)

        def synchronizingThread(name: String, syncs: List[AnyRef]): SyncThread = {
          val ready = new Semaphore(0)
          val proceed = new Semaphore(0)
          val t = new Thread(new Runnable {
            def run = try recursiveSync(syncs) catch { case _: InterruptedException ⇒ () }

            def recursiveSync(syncs1: List[AnyRef]) {
              syncs1 match {
                case Nil ⇒ ()
                case x :: xs ⇒ {
                  ready.release()
                  proceed.acquire()
                  x.synchronized { recursiveSync(xs) }
                }
              }
            }
          }, name)
          t.start()
          SyncThread(name, t, ready, proceed)
        }
        val x = new Object()
        val y = new Object()
        val a = synchronizingThread("deadlock-thread-a", List(x, y))
        val b = synchronizingThread("deadlock-thread-b", List(y, x))
        a.ready.acquire()
        b.ready.acquire()
        a.proceed.release()
        b.proceed.release()
        a.ready.acquire()
        b.ready.acquire()
        a.proceed.release()
        b.proceed.release()
        Thread.sleep((expectedTestDuration + 2.seconds).dilated.toMillis)
      }
      report must include("Coroner's Report")
      report must include("Deadlocks found for monitors")
      val deadlockSection = report.split("Deadlocks found for monitors")(1)
      deadlockSection must include("deadlock-thread-a")
      deadlockSection must include("deadlock-thread-b")
    }

  }
}
