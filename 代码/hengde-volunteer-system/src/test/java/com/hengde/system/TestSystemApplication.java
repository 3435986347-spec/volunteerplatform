package com.hengde.system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * system 模块测试启动类（仅测试用，模块对外仍是纯库）。
 *
 * @author hengde
 */
@SpringBootApplication(scanBasePackages = "com.hengde")
@MapperScan("com.hengde.**.dao")
public class TestSystemApplication {
}
