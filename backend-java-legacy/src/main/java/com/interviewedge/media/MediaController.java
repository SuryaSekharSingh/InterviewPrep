package com.interviewedge.media;

import java.security.Principal;
import java.util.Map;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {
  private final MediaService service;

  public MediaController(MediaService service) {
    this.service = service;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> upload(Principal p, @RequestParam("file") MultipartFile file)
      throws java.io.IOException {
    return service.upload(p.getName(), file.getBytes());
  }

  @GetMapping("/{id}")
  public MediaService.Media get(Principal p, @PathVariable String id) {
    return service.owned(p.getName(), id);
  }

  @GetMapping("/{id}/audio")
  public ResponseEntity<Resource> audio(Principal p, @PathVariable String id) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("audio/wav"))
        .header("Cache-Control", "no-store")
        .body(new FileSystemResource(service.path(p.getName(), id)));
  }

  @DeleteMapping("/{id}")
  public Map<String, Object> delete(Principal p, @PathVariable String id) {
    service.delete(p.getName(), id);
    return Map.of("deleted", true);
  }
}
