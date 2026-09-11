package com.vaultsphere.mongodblog.parser.ftdc;

public class FtdcFormatException extends RuntimeException {
    public FtdcFormatException(String message) {
        super(message);
    }

    public FtdcFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
