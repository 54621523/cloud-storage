package demo.cloud.file.mq;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitExchangeConfig {


    public static final String EXCHANGE_NAME = "file.exchange";

    public static final String UPLOAD_EVENT = "file.upload";

    // 生产者只需要确保交换机存在即可，队列和绑定交给消费者自己声明
    @Bean
    public TopicExchange fileExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }
}