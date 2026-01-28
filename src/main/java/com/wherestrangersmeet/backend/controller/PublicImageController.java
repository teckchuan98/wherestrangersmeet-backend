package com.wherestrangersmeet.backend.controller;

import com.wherestrangersmeet.backend.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/images")
@RequiredArgsConstructor
public class PublicImageController {

    private final FileStorageService fileStorageService;

    @GetMapping("/{key:.+}")
    public ResponseEntity<Resource> getImage(@PathVariable String key) {
        try {
            var inputStream = fileStorageService.downloadFile(key);
            InputStreamResource resource = new InputStreamResource(inputStream);

            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG) // Assuming JPEG, or detect from extension
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
