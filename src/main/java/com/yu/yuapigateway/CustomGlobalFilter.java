package com.yu.yuapigateway;

import cn.hutool.core.codec.Base64;
import com.yu.yuapiclientsdk.utils.SignUtils;
import lombok.extern.slf4j.Slf4j;

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
        // todo 不应该在网关层直接进行数据库操作
//        User existUser = userService.getOne(new QueryWrapper<User>().eq("accessKey", accessKey));

        if (!"testAccessKey".equals(accessKey)) {
//            throw new ApiException(ErrorCode.NO_AUTH_ERROR, "无权限");
            return handleNoAuth(response);
        }
        // 构造需要参与签名的参数
//        String secretKey = existUser.getSecretKey();
        String secretKey = "testSecretKey";
        HashMap<String, String> paramsMap = new HashMap<>();
        paramsMap.put("accessKey", accessKey);
        paramsMap.put("timestamp", timestamp);
        paramsMap.put("nonceStr", nonceStr);
        paramsMap.put("body", rawBody);
        // 重新生成签名
        String twiceSignature = SignUtils.signature(timestamp, accessKey, secretKey, nonceStr, rawBody, paramsMap);
        if (!twiceSignature.equals(signature)) {
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
        // todo 从数据库中查询模拟接口是否存在，以及请求方法是否匹配 (还可以校验请求参数)
        // 6、请求转发，调用模拟接口
//        Mono<Void> filter = chain.filter(exchange);
        // 7、响应日志
//        log.info("响应：{}", response.getStatusCode());
        return handleResponse(exchange, chain);

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
    public Mono<Void> handleResponse(ServerWebExchange exchange, GatewayFilterChain chain) {
        try {
            // 获取原始的响应对象
            ServerHttpResponse originalResponse = exchange.getResponse();
            // 获取数据缓冲工厂
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();
            // 获取响应的状态码
            HttpStatus statusCode = originalResponse.getStatusCode();

            // 判断状态码是否为200 OK(按道理来说,现在没有调用,是拿不到响应码的,对这个保持怀疑 沉思.jpg)
            if (statusCode == HttpStatus.OK) {
                // 创建一个装饰后的响应对象(开始穿装备，增强能力)
                ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {

                    // 重写writeWith方法，用于处理响应体的数据
                    // 这段方法就是只要当我们的模拟接口调用完成之后,等它返回结果，
                    // 就会调用writeWith方法,我们就能根据响应结果做一些自己的处理
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        log.info("body instanceof Flux: {}", (body instanceof Flux));
                        // 判断响应体是否是Flux类型
                        if (body instanceof Flux) {
                            Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                            // 返回一个处理后的响应体
                            // (这里就理解为它在拼接字符串,它把缓冲区的数据取出来，一点一点拼接好)
                            return super.writeWith(fluxBody.map(dataBuffer -> {
                                // 8、todo 调用成功，接口次数 + 1
                                // 读取响应体的内容并转换为字节数组
                                byte[] content = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(content);
                                DataBufferUtils.release(dataBuffer);//释放掉内存
                                // 构建日志
                                StringBuilder sb2 = new StringBuilder(200);
                                List<Object> rspArgs = new ArrayList<>();
                                rspArgs.add(originalResponse.getStatusCode());
                                //rspArgs.add(requestUrl);
                                String data = new String(content, StandardCharsets.UTF_8);//data
                                sb2.append(data);
                                // 打印日志
                                log.info("响应结果：" + data);
                                // 将处理后的内容重新包装成DataBuffer并返回
                                return bufferFactory.wrap(content);
                            }));
                        } else {
                            log.error("<--- {} 响应code异常", getStatusCode());
                        }
                        return super.writeWith(body);
                    }
                };
                // 对于200 OK的请求,将装饰后的响应对象传递给下一个过滤器链,并继续处理(设置repsonse对象为装饰过的)
                return chain.filter(exchange.mutate().response(decoratedResponse).build());
            }
            // 对于非200 OK的请求，直接返回，进行降级处理
            return chain.filter(exchange);
        } catch (Exception e) {
            // 处理异常情况，记录错误日志
            log.error("网关处理响应异常" + e);
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