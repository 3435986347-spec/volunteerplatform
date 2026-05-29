package com.hengde.hengdevolunteerapi;

import com.hengde.api.HengdeVolunteerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = HengdeVolunteerApplication.class)
@ActiveProfiles("test")
class HengdeVolunteerApiApplicationTests {

    @Test
    void contextLoads() {
    }

}
