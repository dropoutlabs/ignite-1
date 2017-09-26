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

import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.ml.encog.evolution.replacement.UpdateStrategy;
import org.apache.ignite.ml.math.functions.IgniteSupplier;
import org.encog.ml.MLEncodable;
import org.encog.ml.MLMethod;
import org.encog.ml.data.MLDataSet;
import org.encog.ml.ea.opp.EvolutionaryOperator;

public interface GATrainerInput<T extends MLMethod & MLEncodable> {
    /**
     * Returns dataset which is used as a training set on each of trainer nodes.
     * @return Dataset which is used as a training set on each of trainer nodes.
     */
    MLDataSet mlDataSet(Ignite ignite);

    IgniteSupplier<T> methodFactory();

    int datasetSize();

    int populationSize();

    List<EvolutionaryOperator> evolutionaryOperators();

    UpdateStrategy replaceStrategy();
}
