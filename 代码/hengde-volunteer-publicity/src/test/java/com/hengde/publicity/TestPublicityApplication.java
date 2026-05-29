package com.hengde.publicity;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.hengde")
@MapperScan("com.hengde.**.dao")
public class TestPublicityApplication {
}
