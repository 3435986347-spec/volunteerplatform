package com.hengde.common.testsupport;

import com.hengde.common.sms.SmsService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 测试基座：把短信通道换成<b>只记不发</b>的替身，供各领域模块断言「该发的短信发了、内容对」。
 *
 * <p>用法：{@code @Import(RecordingSmsConfig.class)} 后注入 {@link RecordingSmsService}。
 * 本类经 common 的 test-jar 暴露，与 {@code TestcontainersConfig} 同一套路。</p>
 *
 * <p><b>为什么不直接靠 {@code hengde.sms.enabled=false}</b>：那条开关下真实实现只打一行日志，
 * 测试无从断言「发的是哪条模板、参数填对了没有」——而模板参数填错正是这批通知最容易出的错，
 * 且线上不会报错，只会让短信少一块内容。</p>
 *
 * @author hengde
 */
@TestConfiguration
public class RecordingSmsConfig {

    @Bean
    @Primary
    public RecordingSmsService recordingSmsService() {
        return new RecordingSmsService();
    }

    /** 记录每一次发送，供用例断言。 */
    public static class RecordingSmsService implements SmsService {

        /** 一次发送 = 一个号码 + 模板 ID + 参数 */
        public record Sent(String phone, String templateId, Map<String, String> params) {
        }

        private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(String phone, String templateId, Map<String, String> params) {
            sent.add(new Sent(phone, templateId, params == null ? Map.of() : Map.copyOf(params)));
        }

        @Override
        public void sendVerifyCode(String phone, String code, String scene) {
            send(phone, "verify-code:" + scene, Map.of("code", code));
        }

        /** 全部发送记录（快照）。 */
        public List<Sent> all() {
            synchronized (sent) {
                return List.copyOf(sent);
            }
        }

        /** 按模板 ID 过滤——各用例自己配的模板 ID 就是它的筛选依据。 */
        public List<Sent> byTemplateId(String templateId) {
            return all().stream().filter(s -> s.templateId().equals(templateId)).toList();
        }

        /** 每个用例开头调一次，避免上一个用例的记录串味。 */
        public void clear() {
            sent.clear();
        }
    }
}
