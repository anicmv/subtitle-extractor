package com.github.anicmv.xxl.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author anicmv
 * @date 2026/9/16 10:57
 * @description XXL-JOB 执行器配置参数（xxl.job.*）。
 */
@Data
@ConfigurationProperties("xxl.job")
public class XxlJobProperties {
    private boolean enabled;
    private String adminAddresses;
    private String accessToken;
    private String appName;
    private String address;
    private String ip;
    private int port;
    private String logPath;
    private int logRetentionDays;
}
