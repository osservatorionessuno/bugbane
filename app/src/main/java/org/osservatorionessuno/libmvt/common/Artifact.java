package org.osservatorionessuno.libmvt.common;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public abstract class Artifact {
    public final List<Object> results = new ArrayList<>();
    public final List<Detection> detected = new ArrayList<>();
    public Indicators indicators;

    public abstract void parse(InputStream input) throws Exception;

    /** Match parsed results against loaded indicators. */
    public abstract void checkIndicators();

    public void setIndicators(Indicators indicators) { this.indicators = indicators; }
    public List<Object> getResults() { return results; }
    public List<Detection> getDetected() { return detected; }
}
