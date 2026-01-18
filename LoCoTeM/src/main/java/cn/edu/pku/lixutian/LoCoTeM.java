package cn.edu.pku.lixutian;

import com.github.javaparser.ParseException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

import java.io.IOException;

@SpringBootApplication
public class LoCoTeM {

    public static void main(String[] args) throws IOException, ParseException, InterruptedException {
        ApplicationContext context = SpringApplication.run(LoCoTeM.class, args);

    }

}
