package com.harness.core.text;

/** Provider-neutral text token counting strategy. */
public interface TextTokenEstimator {

    int estimate(String text);

    String strategyName();
}
