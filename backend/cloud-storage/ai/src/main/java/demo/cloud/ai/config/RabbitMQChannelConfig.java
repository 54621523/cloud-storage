package demo.cloud.ai.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQChannelConfig {

    public static final String RAG_QUEUE = "chat.message.queue";
    public static final String RAG_EXCHANGE = "chat.message.exchange";

    @Bean
    public Queue chatMessageQueue() {
        return QueueBuilder.durable(RAG_QUEUE).build();
    }

    @Bean
    public DirectExchange chatMessageExchange() {
        return new DirectExchange(RAG_EXCHANGE, true, false);
    }

    @Bean
    public Binding binding(Queue chatMessageQueue, DirectExchange chatMessageExchange) {
        return BindingBuilder.bind(chatMessageQueue).to(chatMessageExchange).with("message.save");
    }
}
