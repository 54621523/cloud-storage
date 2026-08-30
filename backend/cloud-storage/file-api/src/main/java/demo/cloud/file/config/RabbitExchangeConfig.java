package demo.cloud.file.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitExchangeConfig {

    // 生产者只需要确保交换机存在即可，队列和绑定交给消费者自己声明
    @Bean
    public TopicExchange fileExchange() {
        return new TopicExchange("file.exchange", true, false);
    }
}