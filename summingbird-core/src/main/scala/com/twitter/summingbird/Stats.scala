/*
 Copyright 2014 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.twitter.summingbird

import com.twitter.summingbird.option.JobId
import scala.collection.JavaConverters._
import scala.collection.parallel.mutable.ParHashSet
import scala.ref.WeakReference
import scala.util.{ Try => ScalaTry }
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

private[summingbird] trait CounterIncrementor {
  def incrBy(by: Long): Unit
}

private[summingbird] trait PlatformStatProvider {
  // Incrementor for a Counter identified by group/name for the specific jobID
  // Returns an incrementor function for the Counter wrapped in an Option
  // to ensure we catch when the incrementor cannot be obtained for the specified jobID
  def counterIncrementor(jobId: JobId, group: String, name: String): Option[CounterIncrementor]
}

private[summingbird] object SummingbirdRuntimeStats {
  val SCALDING_STATS_MODULE = "com.twitter.summingbird.scalding.ScaldingRuntimeStatsProvider$"

  // Need to explicitly invoke the object initializer on remote node
  // since Scala object initialization is lazy, hence need the absolute object classpath
  private[this] final val platformObjects = List(SCALDING_STATS_MODULE)

  // invoke the ScaldingRuntimeStatsProvider object initializer on remote node
  private[this] lazy val platformsInit =
    platformObjects.foreach { s: String => ScalaTry[Unit] { Class.forName(s) } }

  // A global set of PlatformStatProviders, use Java ConcurrentHashMap to create a thread-safe set
  private val platformStatProviders = ParHashSet[WeakReference[PlatformStatProvider]]()

  def hasStatProviders: Boolean = !platformStatProviders.isEmpty

  def addPlatformStatProvider(pp: PlatformStatProvider): Unit =
    platformStatProviders += new WeakReference(pp)

  def getPlatformCounterIncrementor(jobID: JobId, group: String, name: String): CounterIncrementor = {
    platformsInit
    // Find the PlatformMetricProvider (PMP) that matches the jobID
    // return the incrementor for the Counter specified by group/name
    // We return the first PMP that matches the jobID, in reality there should be only one
    (for {
      provRef <- platformStatProviders
      prov <- provRef.get
      incr <- prov.counterIncrementor(jobID, group, name)
    } yield incr)
      .toList
      .headOption
      .getOrElse(sys.error("Could not find the platform stat provider for jobID " + jobID))
  }
}

private[summingbird] object JobCounters {
  @annotation.tailrec
  private[this] final def getOrElseUpdate[K, V](map: ConcurrentHashMap[K, V], k: K, default: => V): V = {
    val v = map.get(k)
    if (v == null) {
      map.putIfAbsent(k, default)
      getOrElseUpdate(map, k, default)
    } else {
      v
    }
  }

  val registeredCountersForJob: ConcurrentHashMap[JobId, ParHashSet[(String, String)]] =
    new ConcurrentHashMap[JobId, ParHashSet[(String, String)]]()

  def registerCounter(jobID: JobId, group: String, name: String): Unit = {
    if (!SummingbirdRuntimeStats.hasStatProviders) {
      val set = getOrElseUpdate(registeredCountersForJob, jobID, ParHashSet[(String, String)]())
      set += ((group, name))
    }
  }
}
