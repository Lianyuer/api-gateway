//package com.yu.yuapigateway;
//
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.boot.SpringApplication;
//import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
//import org.springframework.boot.autoconfigure.SpringBootApplication;
//import org.springframework.cloud.gateway.route.RouteLocator;
//import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
//import org.springframework.context.annotation.Bean;
//
//@SpringBootApplication
//public class YuapiGatewayApplication {
//
//    @Value("${test.uri}")
//    String uri;
//
//    public static void main(String[] args) {
//        SpringApplication.run(YuapiGatewayApplication.class, args);
//    }
//
////    @Bean
////    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
////        //@formatter:off
////        // String uri = "http://httpbin.org:80";
////        // String uri = "http://localhost:9080";
////        return builder.routes()
////                .route(r -> r.host("**.abc.org").and().path("/anything/png")
////                        .filters(f ->
////                                f.prefixPath("/httpbin")
////                                        .addResponseHeader("X-TestHeader", "foobar"))
////                        .uri(uri)
////                )
////                .route("read_body_pred", r -> r.host("*.readbody.org")
////                        .and().readBody(String.class,
////                                s -> s.trim().equalsIgnoreCase("hi"))
////                        .filters(f -> f.prefixPath("/httpbin")
////                                .addResponseHeader("X-TestHeader", "read_body_pred")
////                        ).uri(uri)
////                )
////                .build();
////        //@formatter:on
////    }
//
//    @Bean
//    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
//        //@formatter:off
//        return builder.routes()
//                .route("path_route", r -> r.path("/get")
//                        .uri("http://httpbin.org"))
//                .route("host_route", r -> r.host("*.myhost.org")
//                        .uri("http://httpbin.org"))
//                .route("rewrite_route", r -> r.host("*.rewrite.org")
//                        .filters(f -> f.rewritePath("/foo/(?<segment>.*)",
//                                "/${segment}"))
//                        .uri("http://httpbin.org"))
//                .route("circuitbreaker_route", r -> r.host("*.circuitbreaker.org")
//                        .filters(f -> f.circuitBreaker(c -> c.setName("slowcmd")))
//                        .uri("http://httpbin.org"))
//                .route("circuitbreaker_fallback_route", r -> r.host("*.circuitbreakerfallback.org")
//                        .filters(f -> f.circuitBreaker(c -> c.setName("slowcmd").setFallbackUri("forward:/circuitbreakerfallback")))
//                        .uri("http://httpbin.org"))
//                .route("limit_route", r -> r
//                        .host("*.limited.org").and().path("/anything/**")
//                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(redisRateLimiter())))
//                        .uri("http://httpbin.org"))
//                .route("websocket_route", r -> r.path("/echo")
//                        .uri("ws://localhost:9000"))
//                .build();
//        //@formatter:on
//    }
//
//    @Bean
//    RedisRateLimiter redisRateLimiter() {
//        return new RedisRateLimiter(1, 2);
//    }
//
//}

package com.yu.yuapigateway;

import com.yu.yuapigateway.provider.DemoService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@EnableDubbo
public class YuapiGatewayApplication {

    @DubboReference
    private DemoService demoService;

    public static void main(String[] args) {

        ConfigurableApplicationContext context = SpringApplication.run(YuapiGatewayApplication.class, args);
        YuapiGatewayApplication application = context.getBean(YuapiGatewayApplication.class);
        String result = application.doSayHello("world");
        String result2 = application.doSayHello2("world");
        System.out.println("result: " + result);
        System.out.println("result: " + result2);
    }

    public String doSayHello(String name) {
        return demoService.sayHello(name);
    }

    public String doSayHello2(String name) {
        return demoService.sayHello2(name);
    }

    //    @Bean
    //    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
    //        return builder.routes()
    //                .route("tobaidu", r -> r.path("/baidu")
    //                        .uri("https://www.baidu.com"))
    //                .route("toyupiicu", r -> r.path("/yupiicu")
    //                        .uri("http://yupi.icu"))
    //                .build();
    //    }

}