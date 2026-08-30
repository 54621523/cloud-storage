package demo.cloud.file.config;


import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class RabbitMQConfig {

    // 1. 定义交换机（Topic类型，支持路由）
    @Bean
    public TopicExchange fileExchange() {
        return new TopicExchange("file.exchange", true, false);
    }

    // 2. 定义死信交换机
    @Bean
    public DirectExchange fileDlxExchange() {
        return new DirectExchange("file.dlx.exchange", true, false);
    }

    // 3. 定义死信队列（存放处理失败的消息）
    @Bean
    public Queue fileDlxQueue() {
        return QueueBuilder.durable("file.dlx.queue").build();
    }

    // 4. 绑定死信队列到死信交换机
    @Bean
    public Binding fileDlxBinding() {
        return BindingBuilder.bind(fileDlxQueue()).to(fileDlxExchange()).with("file.dlx.key");
    }

    // 5. 定义核心处理队列，并绑定死信交换机
    @Bean
    public Queue fileProcessQueue() {
        return QueueBuilder.durable("file.process.queue")
                .withArgument("x-dead-letter-exchange", "file.dlx.exchange")   // 失败后发送到死信交换机
                .withArgument("x-dead-letter-routing-key", "file.dlx.key")
                .withArgument("x-message-ttl", 60000) // 可选：消息存活时间，防止堆积太久
                .build();
    }

    // 6. 绑定核心队列到业务交换机
    @Bean
    public Binding fileProcessBinding() {
        return BindingBuilder.bind(fileProcessQueue()).to(fileExchange()).with("file.process");
    }
}