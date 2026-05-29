package com.hengde.organization.biz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.hengde")
@MapperScan("com.hengde.**.dao")
public class TestOrganizationBizApplication {
}
