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

package org.apache.druid.query.aggregation.datasketches.theta;

import org.apache.datasketches.common.Family;
import org.apache.datasketches.theta.SetOperation;
import org.apache.datasketches.theta.Union;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.query.aggregation.Aggregator;
import org.apache.druid.segment.BaseObjectColumnValueSelector;

import javax.annotation.Nullable;
import java.util.List;

public class SketchAggregator implements Aggregator
{

  private final BaseObjectColumnValueSelector selector;
  private final int size;

  @Nullable
  private Union union;

  public SketchAggregator(BaseObjectColumnValueSelector selector, int size)
  {
    this.selector = selector;
    this.size = size;
  }

  private void initUnion()
  {
    union = (Union) SetOperation.builder().setNominalEntries(size).build(Family.UNION);
  }

  @Override
  public void aggregate()
  {
    Object update = selector.getObject();
    if (update == null) {
      return;
    }
    synchronized (this) {
      if (union == null) {
        initUnion();
      }
      updateUnion(union, update);
    }
  }

  @Override
  public long aggregateWithSize()
  {
    Object update = selector.getObject();
    if (update == null) {
      return 0;
    }
    synchronized (this) {
      long unionSizeDelta = 0;
      long initialSketchSize = 0;
      if (union == null) {
        initUnion();

        // Size of UnionImpl = 16B (object header) + 8B (sketch ref) + 2B (short)
        // + 8B (long) + 1B (boolean) + 5B (padding) = 40B
        unionSizeDelta = 40L;
      } else {
        initialSketchSize = union.getCurrentBytes();
      }

      updateUnion(union, update);

      long sketchSizeDelta = union.getCurrentBytes() - initialSketchSize;
      return sketchSizeDelta + unionSizeDelta;
    }
  }

  @Override
  public Object get()
  {
    if (union == null) {
      return SketchHolder.EMPTY;
    }
    //in the code below, I am returning SetOp.getResult(true, null)
    //"true" returns an ordered sketch but slower to compute than unordered sketch.
    //however, advantage of ordered sketch is that they are faster to "union" later
    //given that results from the aggregator will be combined further, it is better
    //to return the ordered sketch here
    synchronized (this) {
      return SketchHolder.of(union.getResult(true, null));
    }
  }

  @Override
  public float getFloat()
  {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public long getLong()
  {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public double getDouble()
  {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public void close()
  {
    union = null;
  }

  static void updateUnion(Union union, Object update)
  {
    if (update instanceof SketchHolder) {
      ((SketchHolder) update).updateUnion(union);
    } else if (update instanceof String) {
      union.update((String) update);
    } else if (update instanceof byte[]) {
      union.update((byte[]) update);
    } else if (update instanceof Double) {
      union.update((Double) update);
    } else if (update instanceof Integer || update instanceof Long) {
      union.update(((Number) update).longValue());
    } else if (update instanceof int[]) {
      union.update((int[]) update);
    } else if (update instanceof long[]) {
      union.update((long[]) update);
    } else if (update instanceof List) {
      for (Object entry : (List) update) {
        if (entry != null) {
          final String asString = entry.toString();
          if (asString != null) {
            union.update(asString);
          }
        }
      }
    } else {
      throw new ISE("Illegal type received while theta sketch merging [%s]", update.getClass());
    }
  }

  /**
   * Gets the initial size of this aggregator in bytes.
   */
  public long getInitialSizeBytes()
  {
    // Size = 16B (object header) + 24B (3 refs) + 4B (int size) = 44B
    // Due to 8-byte alignment, size = 48B
    // (see https://www.baeldung.com/java-memory-layout)
    return 48L;
  }

}
