package com.harness.core.runtime;

/** Creates an isolated trace for each agent run. */
@FunctionalInterface
public interface RunTraceFactory {

    RunTrace start();
}
