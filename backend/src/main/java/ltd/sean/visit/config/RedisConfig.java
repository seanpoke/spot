package ltd.sean.visit.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 模板配置。
 *
 * <p>连接参数（host / port / database / password）全部在 application.yml 里从环境变量读，
 * 这里只管序列化方式，避免 key 出现 {@code \xac\xed...} 这种 JDK 序列化乱码。
 *
 * <p>DB 号固定 3（AGENTS.md 第 9 条）：服务器上 Redis 是五个项目共用的，
 * 库号 + {@code visit:} 前缀是唯一的隔离手段。
 */
@Configuration
public class RedisConfig {

    /**
     * 通用模板：key 用字符串（方便 redis-cli 直接查），value 用 JSON。
     *
     * <p>开启 default typing 是为了反序列化时能还原具体类型（缓存 DTO 时必需）；
     * 个人项目只有一个用户，这里不额外收紧白名单。
     * 只在 {@code ltd.sean.visit} 包内使用，别拿去存不可信的外部数据。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                       ObjectMapper objectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(jsonMapper(objectMapper));

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    private ObjectMapper jsonMapper(ObjectMapper base) {
        ObjectMapper mapper = base.copy();
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfSubType(Object.class).build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}
