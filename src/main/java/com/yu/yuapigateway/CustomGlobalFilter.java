package com.yu.yuapigateway;

import cn.hutool.core.codec.Base64;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.yu.apicommon.model.entity.InterfaceInfo;
import com.yu.apicommon.model.entity.User;
import com.yu.apicommon.service.InnerInterfaceInfoService;
import com.yu.apicommon.service.InnerUserInterfaceInfoService;
import com.yu.apicommon.service.InnerUserService;
import com.yu.yuapiclientsdk.utils.SignUtils;
import lombok.extern.slf4j.Slf4j;

import org.apache.dubbo.config.annotation.DubboReference;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 全局过滤
 */
@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @DubboReference
    private InnerInterfaceInfoService innerInterfaceInfoService;

    @DubboReference
    private InnerUserInterfaceInfoService innerUserInterfaceInfoService;

    @DubboReference
    private InnerUserService innerUserService;

    // IP 白名单
    private static final List<String> IP_WHITE_LIST = Arrays.asList("127.0.0.1");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        log.info("custom global filter");
        // 1、用户发送请求到 API 网关
        // 2、请求日志
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        String method = request.getMethod().toString();
        log.info("请求唯一标识: {}", request.getId());
        log.info("请求路径: {}", path);
        log.info("请求方法: {}", method);
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
        HttpHeaders headers = request.getHeaders();
        String accessKey = headers.getFirst("accessKey");
        String timestamp = headers.getFirst("timestamp");
        String nonceStr = headers.getFirst("nonceStr");
        String encodedBody = headers.getFirst("body");
        String rawBody = null;
        if (encodedBody != null) {
            rawBody = new String(Base64.decode(encodedBody), StandardCharsets.UTF_8);
        }
        String signature = headers.getFirst("signature");
        // 从数据库中查询secretKey
        // todo 不应该在网关层直接进行数据库操作，调用公共服务去请求数据
//        User existUser = userService.getOne(new QueryWrapper<User>().eq("accessKey", accessKey));
        User invokeUser = null;
        try {
            invokeUser = innerUserService.getInvokeUser(accessKey);
        } catch (Exception e) {
            log.error("getInvokeUser error!");
        }
        if (invokeUser == null) {
//            throw new ApiException(ErrorCode.NO_AUTH_ERROR, "无权限");
            return handleNoAuth(response);
        }
        // todo 判断用户调用次数是否足够，不够则无权限进行调用
        // 构造需要参与签名的参数
//        String secretKey = existUser.getSecretKey();
        // 从获取到的用户信息中获取secretKey，再次进行签名比对
        String secretKey = invokeUser.getSecretKey();
        HashMap<String, String> paramsMap = new HashMap<>();
        paramsMap.put("accessKey", accessKey);
        paramsMap.put("timestamp", timestamp);
        paramsMap.put("nonceStr", nonceStr);
        paramsMap.put("body", rawBody);
        // 重新生成签名
        String twiceSignature = SignUtils.signature(timestamp, accessKey, secretKey, nonceStr, rawBody, paramsMap);
        if (signature == null || !signature.equals(twiceSignature)) {
//            throw new ApiException(ErrorCode.SIGN_ERROR, "签名错误");
            return handleNoAuth(response);
        }
        // 请求 5 分钟有效期
        // 首先,获取当前时间的时间戳,以秒为单位
        // System.currentTimeMillis()返回当前时间的毫秒数，除以1000后得到当前时间的秒数。
        Long currentTime = System.currentTimeMillis() / 1000;
        // 定义一个常量FIVE_MINUTES,表示五分钟的时间间隔(乘以60,将分钟转换为秒,得到五分钟的时间间隔)。
        final long FIVE_MINUTES = 60 * 5L;
        if (timestamp != null && currentTime - Long.parseLong(timestamp) > FIVE_MINUTES) {
//            throw new ApiException(ErrorCode.EXPIRED_ERROR, "请求过期");
            return handleNoAuth(response);
        }
        // 从 redis 中获取随机数，如果 5 分钟内获取到相同随机数，则校验不通过
        String nonceStrKey = "api:nonceStr:" + accessKey + ":" + nonceStr;
        String nonceStrCache = stringRedisTemplate.opsForValue().get(nonceStrKey);
        if (nonceStr.equals(nonceStrCache)) {
//            throw new ApiException(ErrorCode.REPEAT_ERROR, "重复请求");
            return handleNoAuth(response);
        } else {
            stringRedisTemplate.opsForValue().set(nonceStrKey, nonceStr, FIVE_MINUTES, TimeUnit.SECONDS);
        }
        // 5、请求的模拟接口是否存在？
        // todo 调用公共服务，从数据库中查询模拟接口是否存在，以及请求方法是否匹配 (还可以校验请求参数)
        InterfaceInfo interfaceInfo = null;
        try {
            interfaceInfo = innerInterfaceInfoService.getInterfaceInfo(path, method);
        } catch (Exception e) {
            log.error("getInterfaceInfo error！");
        }
        if (interfaceInfo == null) {
            return handleNoAuth(response);
        }
        // 6、请求转发，调用模拟接口
//        Mono<Void> filter = chain.filter(exchange);
        // 7、响应日志
//        log.info("响应：{}", response.getStatusCode());
        return handleResponse(exchange, chain, interfaceInfo.getId(), invokeUser.getId());

    }

    @Override
    public int getOrder() {
        return -1;
    }

    /**
     * 处理响应
     *
     * @param exchange
     * @param chain
     * @return
     */
    public Mono<Void> handleResponse(ServerWebExchange exchange, GatewayFilterChain chain, long interfaceInfoId, long userId) {
        try {
            // 获取原始的响应对象
            ServerHttpResponse originalResponse = exchange.getResponse();
            // 获取数据缓冲工厂
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();

            // 创建一个装饰后的响应对象
            ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {

                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    log.info("body instanceof Flux: {}", (body instanceof Flux));

                    // 判断响应体是否是Flux类型
                    if (body instanceof Flux) {
                        Flux<? extends DataBuffer> fluxBody = Flux.from(body);

                        // 使用join收集完整的响应体数据
                        return DataBufferUtils.join(fluxBody)
                                .flatMap(dataBuffer -> {
                                    try {
                                        // 先尝试调用次数+1
                                        innerUserInterfaceInfoService.invokeCount(interfaceInfoId, userId);

                                        // 调用成功，正常返回响应数据
                                        byte[] content = new byte[dataBuffer.readableByteCount()];
                                        dataBuffer.read(content);
                                        DataBufferUtils.release(dataBuffer);

                                        // 打印成功日志
                                        String data = new String(content, StandardCharsets.UTF_8);
                                        log.info("响应结果：" + data);

                                        // 返回原始响应
                                        return super.writeWith(Mono.just(bufferFactory.wrap(content)));

                                    } catch (Exception e) {
                                        log.error("invokeCount error!", e);

                                        // 释放原始数据缓冲区
                                        DataBufferUtils.release(dataBuffer);

                                        // 构造自定义错误响应
                                        // 这里可以根据具体错误类型构造不同的错误信息
                                        String errorMessage = "调用次数不足";

                                        // 构造错误响应的JSON格式
                                        Map<String, Object> errorResponse = new HashMap<>();
                                        errorResponse.put("code", 500);
                                        errorResponse.put("message", errorMessage);
                                        errorResponse.put("timestamp", System.currentTimeMillis());

                                        // 使用Hutool的JSONUtil转换为JSON字符串
                                        String errorJson = JSONUtil.toJsonStr(errorResponse);

                                        // 设置响应状态码和响应头
                                        originalResponse.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                                        originalResponse.getHeaders().setContentType(MediaType.APPLICATION_JSON);

                                        // 返回错误响应
                                        DataBuffer errorBuffer = bufferFactory.wrap(errorJson.getBytes(StandardCharsets.UTF_8));
                                        return super.writeWith(Mono.just(errorBuffer));
                                    }
                                });
                    }
                    return super.writeWith(body);
                }

                // 可选：重写writeAndFlushWith方法以处理更复杂的场景
                @Override
                public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                    return writeWith(Flux.from(body).flatMapSequential(p -> p));
                }
            };

            // 将装饰后的响应对象传递给下一个过滤器链
            return chain.filter(exchange.mutate().response(decoratedResponse).build());

        } catch (Exception e) {
            // 处理异常情况，记录错误日志
            log.error("网关处理响应异常", e);
            return chain.filter(exchange);
        }
    }

    public Mono<Void> handleNoAuth(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return response.setComplete();
    }

    public Mono<Void> handleInvokeError(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        return response.setComplete();
    }
}