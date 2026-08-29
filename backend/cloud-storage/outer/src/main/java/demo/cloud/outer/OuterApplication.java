package demo.cloud.outer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
        "demo.cloud.outer",
        "demo.cloud.common.web.config",
        "demo.cloud.common.web.filter",
        "demo.cloud.common.config"})
class OuterApplication {

    public static void main(String[] args) {
        SpringApplication.run(OuterApplication.class, args);
    }

}
