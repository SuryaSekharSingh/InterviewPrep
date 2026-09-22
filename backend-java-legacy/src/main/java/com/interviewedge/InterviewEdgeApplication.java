package com.interviewedge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    exclude =
        org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class InterviewEdgeApplication {
  public static void main(String[] args) {
    SpringApplication.run(InterviewEdgeApplication.class, args);
  }
}
