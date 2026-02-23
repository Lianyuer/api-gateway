package com.yu.yuapigateway;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 全局过滤
 */
@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    // IP 白名单
    private static final List<String> IP_WHITE_LIST = Arrays.asList("127.0.0.1");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        log.info("custom global filter");
        // 1、用户发送请求到 API 网关
        // 2、请求日志
        ServerHttpRequest request = exchange.getRequest();
        log.info("请求唯一标识: {}", request.getId());
        log.info("请求路径: {}", request.getPath().value());
        log.info("请求方法: {}", request.getMethod());
        log.info("请求参数: {}", request.getQueryParams());
        String sourceAddress = Objects.requireNonNull(request.getLocalAddress()).getHostString();
        log.info("请求来源地址: {}", sourceAddress);
        log.info("请求来源地址: {}", request.getRemoteAddress());

        // 拿到响应对象
        ServerHttpResponse response = exchange.getResponse();
        // 3、(黑白名单)
        if (!IP_WHITE_LIST.contains(sourceAddress)) {
            // 设置响应状态码为 403 Forbidden，禁止访问
            response.setStatusCode(HttpStatus.FORBIDDEN);
            // 返回处理完成的响应
            return response.setComplete();
        }
        // 4、用户鉴权 (判断 ak、sk 是否合法)
        // 5、请求的模拟接口是否存在？
        // 6、请求转发，调用模拟接口
        // 7、响应日志
        // 8、调用成功，接口次数 + 1
        // 9、调用失败，返回一个规范的错误码
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}