/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.ml.encog;

import java.io.Serializable;

/**
 * Key(layer, output neuron, input neuron) for locking mask.
 *
 * Used for simulate topology changes.
 */
public class LockKey implements Serializable {
    int layer;
    int neuron;

    /**
     * @param layer Layer.
     * @param neuron In.
     */
    public LockKey(int layer, int neuron) {
        this.layer = layer;
        this.neuron = neuron;
    }

    public int layer() {
        return layer;
    }

    public int neuron() {
        return neuron;
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        if (this == o)
            return true;

        if (o == null || getClass() != o.getClass())
            return false;

        LockKey that = (LockKey)o;

        return layer == that.layer && neuron == that.neuron;
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return super.hashCode();
    }
}
