/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.lineage;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.runtime.controlprogram.context.SparkExecutionContext;
import org.apache.sysds.runtime.lineage.LineageCacheConfig.LineageCacheStatus;

import java.util.Map;
import java.util.TreeSet;

public class LineageSparkCacheEviction
{
	private static long SPARK_STORAGE_LIMIT = 0; //60% (upper limit of Spark unified memory)
	private static long _sparkStorageSize = 0; //current size
	private static TreeSet<LineageCacheEntry> weightedQueue = new TreeSet<>(LineageCacheConfig.LineageCacheComparator);

	protected static void resetEviction() {
		_sparkStorageSize = 0;
		weightedQueue.clear();
	}

	//--------------- CACHE MAINTENANCE & LOOKUP FUNCTIONS --------------//

	// This method is called at the first cache hit.
	protected static void addEntry(LineageCacheEntry entry, long estimatedSize) {
		if (entry.isNullVal())
			// Placeholders shouldn't participate in eviction cycles.
			return;

		entry.initiateScoreSpark(LineageCacheEviction._removelist, estimatedSize);
		weightedQueue.add(entry);
	}

	protected static void maintainOrder(LineageCacheEntry entry) {
		// Reset the timestamp to maintain the LRU component of the scoring function
		if (LineageCacheConfig.isTimeBased()) {
			if (weightedQueue.remove(entry)) {
				entry.updateTimestamp();
				weightedQueue.add(entry);
			}
		}
		// Scale score of the sought entry after every cache hit
		// FIXME: avoid when called from partial reuse methods
		if (LineageCacheConfig.isCostNsize()) {
			// Exists in weighted queue only if already marked for persistent
			if (weightedQueue.remove(entry)) {
				// Score stays same if not persisted (i.e. size == 0)
				entry.updateScore(true);
				weightedQueue.add(entry);
			}
		}
	}

	protected static void removeSingleEntry(Map<LineageItem, LineageCacheEntry> cache, LineageCacheEntry e) {
		// Keep in cache. Just change the status to be persisted on the next hit
		e.setCacheStatus(LineageCacheStatus.TOPERSISTRDD);
		// Mark for lazy unpersisting
		JavaPairRDD<?,?> rdd = e.getRDDObject().getRDD();
		rdd.unpersist(false);
		// Maintain the current size
		_sparkStorageSize -= e.getSize();
		// Maintain miss count to increase the score if the item enters the cache again
		LineageCacheEviction._removelist.merge(e._key, 1, Integer::sum);

		if (DMLScript.STATISTICS)
			LineageCacheStatistics.incrementRDDUnpersists();
		// NOTE: The caller of this method maintains the eviction queue.
	}

	private static void removeEntry(Map<LineageItem, LineageCacheEntry> cache, LineageCacheEntry e) {
		if (e._origItem == null) {
			// Single entry. Remove.
			removeSingleEntry(cache, e);
			return;
		}

		// Defer the eviction till all the entries with the same intermediate are evicted.
		e.setCacheStatus(LineageCacheStatus.TODELETE);

		boolean del = false;
		LineageCacheEntry tmp = cache.get(e._origItem);
		while (tmp != null) {
			if (tmp.getCacheStatus() != LineageCacheStatus.TODELETE)
				return; //do nothing
			del |= (tmp.getCacheStatus() == LineageCacheStatus.TODELETE);
			tmp = tmp._nextEntry;
		}
		if (del) {
			tmp = cache.get(e._origItem);
			while (tmp != null) {
				removeSingleEntry(cache, tmp);
				tmp = tmp._nextEntry;
			}
		}
	}

	//---------------- CACHE SPACE MANAGEMENT METHODS -----------------//

	private static void setSparkStorageLimit() {
		// Set the limit only during the first RDD caching to avoid context creation
		// Cache size = 70% of unified Spark memory = 0.7 * 0.6 = 42%.
		if (SPARK_STORAGE_LIMIT == 0) {
			long unifiedSparkMem = (long) SparkExecutionContext.getDataMemoryBudget(false, true);
			SPARK_STORAGE_LIMIT = (long)(unifiedSparkMem * 0.7d);
		}
	}

	protected static double getSparkStorageLimit() {
		if (SPARK_STORAGE_LIMIT == 0)
			setSparkStorageLimit();
		return SPARK_STORAGE_LIMIT;
	}

	protected static void updateSize(long space, boolean addspace) {
		_sparkStorageSize += addspace ? space : -space;
		// NOTE: this doesn't represent the true size as we maintain total size based on estimations
	}

	protected static boolean isBelowThreshold(long estimateSize) {
		boolean available = (estimateSize + _sparkStorageSize) <= getSparkStorageLimit();
		if (!available)
			// Get exact storage used (including checkpoints from outside of lineage)
			_sparkStorageSize = SparkExecutionContext.getStorageSpaceUsed();

		return  (estimateSize + _sparkStorageSize) <= getSparkStorageLimit();
	}

	protected static void makeSpace(Map<LineageItem, LineageCacheEntry> cache, long estimatedSize) {
		// Cost-based eviction
		while ((estimatedSize + _sparkStorageSize) > getSparkStorageLimit()) {
			LineageCacheEntry e = weightedQueue.pollFirst();
			if (e == null)
				// Nothing to evict.
				break;

			removeEntry(cache, e);
		}
	}
}
