package demo.cloud.outer.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        // 1. 创建底层请求工厂，并配置超时时间
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); // 连接超时：5秒
        factory.setReadTimeout(5000);    // 读取超时：5秒

        // 2. 创建 RestTemplate 并注入工厂
        RestTemplate restTemplate = new RestTemplate(factory);

        // 3. 可以在这里添加全局拦截器、自定义消息转换器等
        // restTemplate.getInterceptors().add(new LoggingInterceptor());

        return restTemplate;
    }
}