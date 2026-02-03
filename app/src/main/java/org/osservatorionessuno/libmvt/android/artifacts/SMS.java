package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.AlertLevel;
import org.osservatorionessuno.libmvt.common.Detection;
import org.osservatorionessuno.libmvt.common.Indicators.IndicatorType;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;
import java.io.InputStream;

/**
 * TODO
 */
public class SMS extends AndroidArtifact {

    @Override
    public List<String> paths() {
        return List.of("sms.txt");
    }

    @Override
    public void parse(InputStream input) throws IOException {
        return;
    }

    @Override
    public void checkIndicators() {
        return;
    }
}
