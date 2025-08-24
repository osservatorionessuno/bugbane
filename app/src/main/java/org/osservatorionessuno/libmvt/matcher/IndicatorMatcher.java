package org.osservatorionessuno.libmvt.matcher;

import org.osservatorionessuno.libmvt.common.Detection;
import org.osservatorionessuno.libmvt.common.IndicatorType;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.util.ArrayList;
import java.util.List;

public class IndicatorMatcher {
    private final Indicators indicators;
    public IndicatorMatcher(Indicators indicators) { this.indicators = indicators; }

    public List<Detection> matchAllStrings(List<String> strings) {
        List<Detection> res = new ArrayList<>();
        for (String s : strings) {
            res.addAll(indicators.matchString(s, IndicatorType.URL));
            res.addAll(indicators.matchString(s, IndicatorType.DOMAIN));
            res.addAll(indicators.matchString(s, IndicatorType.PROCESS));
        }
        return res;
    }
}
