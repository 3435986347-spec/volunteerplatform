package com.hengde.auth.service;

import com.hengde.common.sms.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 通知短信的实际发送方：逐个号码发，单个失败不影响其余。
 *
 * <p><b>为什么单独一个 bean、而不是 {@link SmsNotifyService} 的一个私有方法</b>：
 * {@code @Async} 与 {@code @Transactional} 一样靠代理生效，<b>自调用会静默失效</b>
 * （证书那批的 {@code CertificateAutoCreateListener} 记过同一课）。
 * {@link SmsNotifyService} 是在事务提交回调里调它的，必须跨 bean 才真的异步。</p>
 *
 * <p><b>异步是必须的，不是优化</b>：取消一个几百人的活动会产生几百次外部 HTTP 调用，
 * 同步做等于让管理员的「取消活动」请求挂在那里等短信发完。
 * 领域模块的测试上下文没有 {@code @EnableAsync}（它在 api 的 {@code AsyncConfig}），
 * 因此用例里这个方法是<b>同步执行</b>的——正好可以直接断言，不必跟线程赛跑。</p>
 *
 * <p><b>逐号发而不是把号码拼成一串</b>：火山支持逗号分隔的多号码，但那样一个非法号码会让整批被拒，
 * 且日志里分不清是谁失败了。通知短信本就是尽力而为，宁可慢一点也要让失败落到具体的人头上。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class SmsDispatcher {

    private SmsService smsService;

    @Autowired
    public void setSmsService(SmsService smsService) {
        this.smsService = smsService;
    }

    /**
     * 发送一批通知短信。
     *
     * <p><b>不抛异常</b>：调用点在事务提交之后，此时业务已经落定，
     * 再抛出去既回滚不了什么，又会在异步线程里变成一条没人读的堆栈。
     * 失败以 ERROR 记日志（含模板键与号码），由日志告警兜住。</p>
     *
     * @param phones      接收号码（已去重、已去空）
     * @param templateId  火山模板 ID
     * @param params      模板参数
     * @param templateKey 模板配置键，仅用于日志定位
     */
    @Async
    public void dispatch(List<String> phones, String templateId,
                         Map<String, String> params, String templateKey) {
        int failed = 0;
        for (String phone : phones) {
            try {
                smsService.send(phone, templateId, params);
            } catch (Exception e) {
                failed++;
                log.error("[SMS-NOTIFY] 发送失败 template={} phone={}", templateKey, phone, e);
            }
        }
        if (failed > 0) {
            log.error("[SMS-NOTIFY] template={} 共 {} 条，失败 {} 条", templateKey, phones.size(), failed);
        } else {
            log.info("[SMS-NOTIFY] template={} 已发送 {} 条", templateKey, phones.size());
        }
    }
}
