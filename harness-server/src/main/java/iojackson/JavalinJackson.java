package iojackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.json.JsonMapper;

import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * Javalin JSON mapper adapter for Jackson.
 */
public class JavalinJackson implements JsonMapper {

    private final ObjectMapper mapper;

    public JavalinJackson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String toJsonString(Object obj, Type type) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    @Override
    public <T> T fromJsonString(String json, Type targetType) {
        try {
            return mapper.readValue(json, mapper.constructType(targetType));
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failed", e);
        }
    }

    @Override
    public <T> T fromJsonStream(InputStream inputStream, Type targetType) {
        try {
            return mapper.readValue(inputStream, mapper.constructType(targetType));
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failed", e);
        }
    }
}
