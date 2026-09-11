package com.vaultsphere.mongodblog.analysis.ftdc;

import java.util.List;

public record FtdcSeriesResult(String metricId, String path, String view,
                               List<Long> timestamps, List<Long> values) {
    public FtdcSeriesResult {
        timestamps = List.copyOf(timestamps);
        values = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));
    }
}
