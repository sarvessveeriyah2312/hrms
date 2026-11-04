package com.hrms.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Local-development fallback when Firebase is not enabled. Logs the event type only. */
@Configuration(proxyBeanMethods = false)
class PushSenderFallbackConfig {

    private static final Logger log = LoggerFactory.getLogger(PushSenderFallbackConfig.class);

    @Bean
    @ConditionalOnMissingBean(PushSender.class)
    PushSender loggingPushSender() {
        return m -> log.info("Push (not sent, Firebase disabled): event={} link={}", m.event(), m.deepLink());
    }
}
