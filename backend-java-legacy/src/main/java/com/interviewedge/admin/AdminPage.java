package com.interviewedge.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminPage {
  @GetMapping("/admin")
  public String index() {
    return "redirect:/admin/index.html";
  }
}
