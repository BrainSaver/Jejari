package com.jejari.queue.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jejari.queue.api.DevHeaderMemberAuthenticator;
import com.jejari.queue.api.MemberAuthenticator;
import com.jejari.queue.api.QueueTokenFilter;
import com.jejari.queue.domain.PolicyProvider;
import com.jejari.queue.domain.QueueAdmitScheduler;
import com.jejari.queue.domain.QueueAdmitter;
import com.jejari.queue.domain.QueuePassService;
import com.jejari.queue.domain.QueueService;
import com.jejari.queue.domain.RiskPenaltyService;
import com.jejari.queue.port.DevMemberIdentityPort;
import com.jejari.queue.port.DevOnSaleRunProvider;
import com.jejari.queue.port.LoggingQueueLogSink;
import com.jejari.queue.port.MemberIdentityPort;
import com.jejari.queue.port.OnSaleRunProvider;
import com.jejari.queue.port.QueueLogSink;
import io.lettuce.core.ClientOptions;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class QueueConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    /**
     * Redis가 끊기면 명령을 대기열에 쌓아 두지 않고 바로 실패시킨다. 기본값은 재연결을 기다리며 명령을 쌓아 두어
     * 요청마다 스레드를 60초 붙잡고, 그 사이 앱 전체가 멈춘다. (재연결은 계속 시도한다.)
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer lettuceFailFast() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
    }

    // ---- Lua 스크립트 ----

    @Bean
    RedisScript<List> enterScript() { return script("lua/enter.lua"); }

    @Bean
    RedisScript<List> admitScript() { return script("lua/admit.lua"); }

    @Bean
    RedisScript<List> leaveScript() { return script("lua/leave.lua"); }

    @Bean
    RedisScript<List> statusScript() { return script("lua/status.lua"); }

    private static RedisScript<List> script(String path) {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(path));
        s.setResultType(List.class);
        return s;
    }

    // ---- 서비스 ----

    @Bean
    PolicyProvider policyProvider(StringRedisTemplate redis,
                                  @Value("${jejari.queue.policy-cache-ms:1000}") long cacheMs) {
        return new PolicyProvider(redis, cacheMs);
    }

    @Bean
    RiskPenaltyService riskPenaltyService(StringRedisTemplate redis, PolicyProvider policy) {
        return new RiskPenaltyService(redis, policy);
    }

    @Bean
    QueueService queueService(StringRedisTemplate redis, RedisScript<List> enterScript, RedisScript<List> leaveScript,
                              RedisScript<List> statusScript, PolicyProvider policy, RiskPenaltyService risk, MemberIdentityPort members,
                              OnSaleRunProvider runs, QueueLogSink logSink, Clock clock) {
        return new QueueService(redis, enterScript, leaveScript, statusScript, policy, risk, members, runs, logSink, clock);
    }

    @Bean
    QueueAdmitter queueAdmitter(StringRedisTemplate redis, RedisScript<List> admitScript, PolicyProvider policy,
                                QueueLogSink logSink, Clock clock) {
        return new QueueAdmitter(redis, admitScript, policy, logSink, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "jejari.queue.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    QueueAdmitScheduler queueAdmitScheduler(OnSaleRunProvider runs, QueueAdmitter admitter) {
        return new QueueAdmitScheduler(runs, admitter);
    }

    @Bean
    QueuePassService queuePassService(StringRedisTemplate redis) {
        return new QueuePassService(redis);
    }

    @Bean
    QueueTokenFilter queueTokenFilter(QueuePassService pass, MemberAuthenticator auth, ObjectMapper mapper) {
        return new QueueTokenFilter(pass, auth, mapper);
    }

    // ---- 포트 기본값 ----

    @Bean
    @ConditionalOnMissingBean
    QueueLogSink queueLogSink() {
        return new LoggingQueueLogSink();
    }

    /**
     * 아래 세 개는 개발용 대용품이다. jejari.dev-mode=true일 때만 만들어지고, 운영에서는 실제 구현
     * (JWT 인증, member·train_run 조회)이 없으면 앱이 뜨지 않는다. 대용품이 조용히 운영에 쓰이는 것을 막으려는 설계다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "true")
    MemberAuthenticator devMemberAuthenticator() {
        return new DevHeaderMemberAuthenticator();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "true")
    DevMemberIdentityPort devMemberIdentityPort() {
        return new DevMemberIdentityPort();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "true")
    DevOnSaleRunProvider devOnSaleRunProvider(@Value("${jejari.queue.dev-on-sale-runs:}") String runs) {
        DevOnSaleRunProvider p = new DevOnSaleRunProvider();
        for (String s : runs.split(",")) {
            if (!s.isBlank()) {
                p.open(Long.parseLong(s.trim()));
            }
        }
        return p;
    }
}
