package ksh.tryptoengine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Value("${engine.inbox.queue}")
    private String inboxQueue;

    @Value("${engine.publisher.fanout-exchange}")
    private String fanoutExchange;

    @Bean
    public Queue engineInboxQueue() {
        return new Queue(inboxQueue, true, false, false);
    }

    @Bean
    public FanoutExchange orderFilledExchange() {
        return new FanoutExchange(fanoutExchange, true, false);
    }

    @Bean
    public ObjectMapper engineObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter(ObjectMapper engineObjectMapper) {
        return new Jackson2JsonMessageConverter(engineObjectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf, Jackson2JsonMessageConverter conv) {
        RabbitTemplate t = new RabbitTemplate(cf);
        t.setMessageConverter(conv);
        return t;
    }

    @Bean
    public SimpleMessageListenerContainer inboxListenerContainer(ConnectionFactory cf) {
        SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(cf);
        c.setQueueNames(inboxQueue);
        c.setConcurrentConsumers(1);
        c.setMaxConcurrentConsumers(1);
        c.setAcknowledgeMode(AcknowledgeMode.AUTO);
        return c;
    }
}
