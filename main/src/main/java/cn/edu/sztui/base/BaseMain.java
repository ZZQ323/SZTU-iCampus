package cn.edu.sztui.base;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableScheduling
@SpringBootApplication(scanBasePackages = "cn.edu.sztui")
public class BaseMain {
    public static void main(String[] args) {
        try {
            SpringApplication.run(BaseMain.class, args);
            log.info("启动完成!");
        } catch (Exception e) {
            log.error("启动出现问题！" + e.getMessage());
        }
    }
}
