package org.osservatorionessuno.libmvt.common;

import java.util.ArrayList;
import java.util.List;

public abstract class Artifact {
    protected final List<Object> results = new ArrayList<>();
    protected final List<Detection> detected = new ArrayList<>();
    protected Indicators indicators;

    public abstract void parse(String input) throws Exception;

    /** Match parsed results against loaded indicators. */
    public abstract void checkIndicators();

    public void setIndicators(Indicators indicators) { this.indicators = indicators; }
    public List<Object> getResults() { return results; }
    public List<Detection> getDetected() { return detected; }
}
