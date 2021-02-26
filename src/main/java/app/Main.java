package app;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Configuration
@ComponentScan("app")
@PropertySource("classpath:application.yaml")
public class Main {

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper om = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private JsonNode root;
    @Value("${url}")
    private String url;

    public static void main(String[] args) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(Main.class);
        ctx.refresh();
    }

    @SneakyThrows
    @PostConstruct
    public void run() {
        root = readFromFile();
        System.out.println("mandatory fields: ");
        iterate(root.deepCopy(), Collections.emptyList());
    }

    private void iterate(JsonNode parent, final List<String> paths) {
        Iterator<Map.Entry<String, JsonNode>> entries = parent.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String fieldName = entry.getKey();
            JsonNode fieldNode = entry.getValue();

            runNestedNodes(fieldNode, fieldName, paths);
            ObjectNode body = prepareBody(fieldName, paths);
            send(body, fieldName, paths);
        }
    }

    private void runNestedNodes(JsonNode node, String fieldName, List<String> paths) {
        if (isNestedNodes(node)) {
            ArrayList<String> fields = new ArrayList<>(paths);
            fields.add(fieldName);
            iterate(node.deepCopy(), fields);
        }
    }

    private boolean isNestedNodes(JsonNode node) {
        return node.getNodeType().name().equals("OBJECT");
    }

    private ObjectNode prepareBody(String fieldName, List<String> paths) {
        ObjectNode body = root.deepCopy();
        if (!paths.isEmpty()) {
            JsonNode temp = body;
            for (String s : paths)
                temp = temp.path(s);
            ((ObjectNode) temp).remove(fieldName);
        } else {
            body.remove(fieldName);
        }
        return body;
    }

    private void send(JsonNode body, String fieldName, List<String> paths) {
        try {
            String result = request(url, body, fieldName).block();
        } catch (IllegalStateException e) {
            prettyPrint(fieldName, paths);
        }
    }

    private void prettyPrint(String fieldName, List<String> paths) {
        String path = paths.stream().reduce((s, s2) -> s + "/" + s2).orElseGet(String::new);
        System.out.println(path + " " + fieldName);
    }

    @SneakyThrows
    private JsonNode readFromFile() {
        Path path = Paths.get("payload.json");
        String json = new String(Files.readAllBytes(path));
        return om.readTree(json);
    }

    private Mono<String> request(String url, JsonNode body, String deletedField) {
        if (url == null || url.isEmpty()) {
            System.out.println("url is empty!!");
            return Mono.just("");
        }
        return webClient.method(HttpMethod.POST)
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(body), JsonNode.class)
                .retrieve()
                .onStatus(HttpStatus::isError, response -> {
                    String s = String.format("Error while calling endpoint %s with status code %s without mandatory field %s",
                            url, response.statusCode(), deletedField);
                    return Mono.error(new IllegalStateException(s));
                })
                .bodyToMono(String.class);
    }
}
