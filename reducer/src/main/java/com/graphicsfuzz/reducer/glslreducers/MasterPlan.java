/*
 * Copyright 2018 The GraphicsFuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.graphicsfuzz.reducer.glslreducers;

import com.graphicsfuzz.common.util.ShaderKind;
import com.graphicsfuzz.reducer.IReductionState;
import com.graphicsfuzz.reducer.reductionopportunities.IReductionOpportunityFinder;
import com.graphicsfuzz.reducer.reductionopportunities.ReductionOpportunityContext;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MasterPlan implements IReductionPlan {

  private static final Logger LOGGER = LoggerFactory.getLogger(MasterPlan.class);

  private static final int MAX_STEPS_PER_PASS = 200;
  private final boolean verbose;
  private final ReductionOpportunityContext reductionOpportunityContext;

  private int fullPassesCompleted;
  private int passIndex;
  private int currentPassSteps;
  private boolean somePassMadeProgress;

  private ShaderKind shaderKind;
  private List<IReductionPlan> plans;

  public MasterPlan(
        ReductionOpportunityContext reductionOpportunityContext,
        boolean verbose) {
    this.verbose = verbose;
    this.reductionOpportunityContext = reductionOpportunityContext;
    this.fullPassesCompleted = 0;
    this.passIndex = 0;
    this.currentPassSteps = 0;
    this.somePassMadeProgress = false;
    this.shaderKind = ShaderKind.FRAGMENT;
    resetPlans();

  }

  private void resetPlans() {
    this.plans = new ArrayList<>();
    for (IReductionOpportunityFinder ops : new IReductionOpportunityFinder[]{
        IReductionOpportunityFinder.vectorizationFinder(),
        IReductionOpportunityFinder.mutationFinder(),
        IReductionOpportunityFinder.unswitchifyFinder(),
        IReductionOpportunityFinder.stmtFinder(),
        IReductionOpportunityFinder.functionFinder(),
        IReductionOpportunityFinder.exprToConstantFinder(),
        IReductionOpportunityFinder.functionFinder(),
        IReductionOpportunityFinder.compoundExprToSubExprFinder(),
        IReductionOpportunityFinder.functionFinder(),
        IReductionOpportunityFinder.loopMergeFinder(),
        IReductionOpportunityFinder.compoundToBlockFinder(),
        IReductionOpportunityFinder.inlineInitializerFinder(),
        IReductionOpportunityFinder.unusedStructFinder(),
        IReductionOpportunityFinder.outlinedStatementFinder(),
        IReductionOpportunityFinder.unwrapFinder(),
        IReductionOpportunityFinder.removeStructFieldFinder(),
        IReductionOpportunityFinder.destructifyFinder(),
        IReductionOpportunityFinder.inlineStructFieldFinder(),
        IReductionOpportunityFinder.liveFragColorWriteFinder(),
        IReductionOpportunityFinder.inlineFunctionFinder(),
        IReductionOpportunityFinder.functionFinder(),
        IReductionOpportunityFinder.declarationFinder(),
        IReductionOpportunityFinder.unusedParamFinder(),
        IReductionOpportunityFinder.foldConstantFinder(),
    }) {
      plans.add(new SimplePlan(reductionOpportunityContext,
            shaderKind,
            verbose,
            ops));
    }
  }

  @Override
  public void update(boolean interesting) {
    if (interesting) {
      somePassMadeProgress = true;
    }
    getCurrentPlan().update(interesting);
  }

  @Override
  public IReductionState applyReduction(IReductionState state)
      throws NoMoreToReduceException {
    while (true) {
      if (currentPassSteps < MAX_STEPS_PER_PASS) {
        // Try the current slave plan.
        try {
          final IReductionState result = getCurrentPlan().applyReduction(state);
          currentPassSteps++;
          return result;
        } catch (NoMoreToReduceException exception) {
          // The current slave plan failed.  Replenish it, in case it is needed again later, and
          // move on to the next plan.
          getCurrentPlan().replenish();
        }
      }

      passIndex++;
      currentPassSteps = 0;

      if (passIndex == plans.size()) {
        // We've done all the passes.
        if (!somePassMadeProgress) {
          // No pass made progress; we have reached a fixed-point for this shader kind.
          if (shaderKind == ShaderKind.VERTEX) {
            throw new NoMoreToReduceException();
          } else if (shaderKind == ShaderKind.FRAGMENT) {
            if (!state.hasVertexShader()) {
              throw new NoMoreToReduceException();
            }
            LOGGER.info("Moving on to reducing vertex shader");
            shaderKind = ShaderKind.VERTEX;
            resetPlans();
            fullPassesCompleted = 0;
            passIndex = 0;
            somePassMadeProgress = false;
          } else {
            throw new RuntimeException("Unknown shader kind " + shaderKind);
          }
        } else {
          fullPassesCompleted++;
          passIndex = 0;
          somePassMadeProgress = false;
        }
      }
      // Having updated the slave plan, try to transform again.
    }
  }

  @Override
  public void replenish() {
    // Do nothing
  }

  public IReductionPlan getCurrentPlan() {
    return plans.get(passIndex);
  }

}
