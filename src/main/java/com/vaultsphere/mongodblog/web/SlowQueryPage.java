package com.vaultsphere.mongodblog.web;

import com.vaultsphere.mongodblog.analysis.SlowQueryRecord;

import java.util.List;

public record SlowQueryPage(
        int page,
        int size,
        long total,
        List<SlowQueryRecord> content
) {
}

