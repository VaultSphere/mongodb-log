package com.vaultsphere.mongodblog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = MongoDbLogAnalyzerApplication.class,
        properties = "mongodblog.data-dir=${java.io.tmpdir}/mongodb-log-context-test"
)
class ApplicationContextTest {

    @Test
    void contextLoads() {
    }
}
