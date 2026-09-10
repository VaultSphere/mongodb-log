package com.vaultsphere.mongodblog.parser;

public interface LogParser {
    ParseOutcome parse(String line, long lineNumber, int fileIndex);
}

