package dev.atul.edge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@SpringBootApplication
@EnableEurekaClient
public class EdgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdgeApplication.class, args);
    }

}

@Configuration
class GatewayConfiguration {

    @Bean
    public RouteLocator gateway(RouteLocatorBuilder rlb) {
        return rlb.routes()
                .route(
                        predicateSpec -> {
                            return predicateSpec.path("/proxy")
                                    .and()
                                    .host("*.spring.io")
                                    .filters(
                                            f -> f.setPath("/customers").setResponseHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                                    )
                                    .uri("lb://customers");

                        }
                ).build();
    }
}

@Configuration
class CrmConfiguration {

    @Bean
    @LoadBalanced
    public WebClient.Builder httpBuilder() {
        return WebClient.builder();
    }

    @Bean
    public WebClient http(WebClient.Builder wc) {
        return wc.build();
    }

    @Bean
    public RSocketRequester rsocket(RSocketRequester.Builder rc) {
        return rc.tcp("localhost", 8082);
    }
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class CustomerOrders {
    private Customer customer;
    private List<Order> orders;
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Order {
    private Integer id;
    private Integer customerId;
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Customer {
    private Integer id;
    private String name;
}

@Component
@RequiredArgsConstructor
class CrmClient {

    private final WebClient http;
    private final RSocketRequester rsocket;

    public Flux<CustomerOrders> customerOrders() {
        return this.customers()
                .flatMap(customer -> {
                    return Mono.zip(Mono.just(customer), this.orders(customer.getId()).collectList());
                })
                .map(co -> new CustomerOrders(co.getT1(), co.getT2()));
    }

    public Flux<Customer> customers() {
        return this.http
                .get().uri("lb://customers/customers")
                .retrieve()
                .bodyToFlux(Customer.class)
                .retry(10)
                .onErrorResume(throwable -> Flux.empty())
                .timeout(Duration.ofSeconds(10));
    }

    public Flux<Order> orders(Integer customerId) {
        return this.rsocket
                .route("orders.{customerId}", customerId)
                .retrieveFlux(Order.class)
                .retry(10)
                .onErrorResume(throwable -> Flux.empty())
                .timeout(Duration.ofSeconds(10));
    }
}

@Controller
@ResponseBody
@RequiredArgsConstructor
class CrmClientRestController {
    private final CrmClient client;

    @GetMapping("/customers")
    public Flux<Customer> customers() {
        return client.customers();
    }

    @GetMapping("/orders/{cid}")
    public Flux<Order> orders(@PathVariable Integer cid) {
        return client.orders(cid);
    }

    @GetMapping("/cos")
    public Flux<CustomerOrders> cos() {
        return client.customerOrders();
    }
}

@Controller
@RequiredArgsConstructor
class CrmClientGraphqlController {

    private final CrmClient client;

    @QueryMapping
    public Flux<Customer> customers() {
        return client.customers();
    }

    @SchemaMapping
    public Flux<Order> orders(Customer customer) {
        return client.orders(customer.getId());
    }

}