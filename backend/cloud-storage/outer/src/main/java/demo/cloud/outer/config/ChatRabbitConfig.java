package demo.cloud.outer.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatRabbitConfig {
    public static final String CHAT_MESSAGE_QUEUE = "chat.message.queue";
    public static final String CHAT_MESSAGE_EXCHANGE = "chat.message.exchange";

    @Bean
    public Queue chatMessageQueue() {
        return QueueBuilder.durable(CHAT_MESSAGE_QUEUE).build();
    }

    @Bean
    public DirectExchange chatMessageExchange() {
        return new DirectExchange(CHAT_MESSAGE_EXCHANGE, true, false);
    }

    @Bean
    public Binding binding(Queue chatMessageQueue, DirectExchange chatMessageExchange) {
        return BindingBuilder.bind(chatMessageQueue).to(chatMessageExchange).with("message.save");
    }
}