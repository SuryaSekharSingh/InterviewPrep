package com.interviewedge.content;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
  private final ContentCatalog catalog;

  public CatalogController(ContentCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  public Map<String, Object> get() {
    return catalog.catalog();
  }
}
