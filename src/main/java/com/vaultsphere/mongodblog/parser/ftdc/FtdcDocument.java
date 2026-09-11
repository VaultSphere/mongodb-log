package com.vaultsphere.mongodblog.parser.ftdc;

public record FtdcDocument(
        long fileOffset,
        int length,
        int type,
        byte[] data
) {
}
