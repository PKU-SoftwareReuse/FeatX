package cn.edu.pku.lixutian.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/connect")
public class ConnectTestController {
    @GetMapping("/test")
    public void connectTest() {

    }
}
