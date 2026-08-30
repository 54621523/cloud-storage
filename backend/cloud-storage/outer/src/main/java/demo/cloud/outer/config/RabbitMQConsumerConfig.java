package demo.cloud.outer.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConsumerConfig {

    @Bean
    public DirectExchange fileDlxExchange() {
        return new DirectExchange("file.dlx.exchange", true, false);
    }

    @Bean
    public Queue fileDlxQueue() {
        return QueueBuilder.durable("file.dlx.queue").build();
    }

    @Bean
    public Binding fileDlxBinding() {
        return BindingBuilder.bind(fileDlxQueue()).to(fileDlxExchange()).with("file.dlx.key");
    }

    @Bean
    public Queue fileProcessQueue() {
        return QueueBuilder.durable("file.process.queue")
                .withArgument("x-dead-letter-exchange", "file.dlx.exchange")
                .withArgument("x-dead-letter-routing-key", "file.dlx.key")
                .withArgument("x-message-ttl", 60000)
                .build();
    }

    @Bean
    public Binding fileProcessBinding() {
        return BindingBuilder.bind(fileProcessQueue())
                .to(new TopicExchange("file.exchange")) // 直接引用生产者那边的交换机名称，或者注入 @Bean 名字
                .with("file.process");
    }
}