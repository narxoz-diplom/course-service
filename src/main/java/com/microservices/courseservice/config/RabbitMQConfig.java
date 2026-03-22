package com.microservices.courseservice.config;

import com.microservices.courseservice.service.VectorCleanupEventPublisher;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String RAG_VECTOR_CLEANUP_QUEUE = "rag.vector.cleanup";

    @Bean
    public Queue notificationQueue() {
        return new Queue("notification.queue", true);
    }

    @Bean
    public TopicExchange ragEventsExchange() {
        return new TopicExchange(VectorCleanupEventPublisher.RAG_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public Queue ragVectorCleanupQueue() {
        return new Queue(RAG_VECTOR_CLEANUP_QUEUE, true);
    }

    @Bean
    public Binding ragVectorCleanupBinding(
            @Qualifier("ragVectorCleanupQueue") Queue ragVectorCleanupQueue,
            TopicExchange ragEventsExchange) {
        return BindingBuilder.bind(ragVectorCleanupQueue)
                .to(ragEventsExchange)
                .with("vector.delete.*");
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }
}

