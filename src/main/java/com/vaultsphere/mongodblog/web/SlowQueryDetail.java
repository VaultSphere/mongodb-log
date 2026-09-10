package com.vaultsphere.mongodblog.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.vaultsphere.mongodblog.analysis.SlowQueryRecord;

public record SlowQueryDetail(
        @JsonUnwrapped SlowQueryRecord query,
        Integer id,
        String severity,
        String component,
        String context,
        String message
) {
}
