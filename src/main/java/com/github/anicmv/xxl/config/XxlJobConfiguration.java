package com.github.anicmv.xxl.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description xxl.job.enabled=true 时装配 XXL-JOB 执行器，并校验调度中心地址已配置。
 */
@Configuration
@ConditionalOnProperty(prefix = "xxl.job", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(XxlJobProperties.class)
public class XxlJobConfiguration {
    @Bean
    XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties properties) {
        if (properties.getAdminAddresses() == null || properties.getAdminAddresses().isBlank()) {
            throw new IllegalArgumentException("启用 XXL-JOB 时必须配置 xxl.job.admin-addresses");
        }
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(properties.getAdminAddresses());
        executor.setAccessToken(properties.getAccessToken());
        executor.setAppname(properties.getAppName());
        executor.setAddress(properties.getAddress());
        executor.setIp(properties.getIp());
        executor.setPort(properties.getPort());
        executor.setLogPath(properties.getLogPath());
        executor.setLogRetentionDays(properties.getLogRetentionDays());
        return executor;
    }
}
