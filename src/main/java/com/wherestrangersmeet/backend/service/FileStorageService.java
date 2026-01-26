package com.wherestrangersmeet.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final S3Presigner s3Presigner;

    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;

    @Value("${cloud.aws.s3.public-url:}")
    private String publicUrlBase;

    private static final List<String> BLOCKED_EXTENSIONS = Arrays.asList(
            ".exe", ".bat", ".cmd", ".sh", ".php", ".pl", ".cgi");

    public Map<String, String> generatePresignedUploadUrl(String folder, String originalFilename) {
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        // Simple security check
        if (BLOCKED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new RuntimeException("File type not allowed");
        }

        String prefix = (folder != null && !folder.isEmpty()) ? (folder.endsWith("/") ? folder : folder + "/") : "";
        String key = prefix + UUID.randomUUID().toString() + extension;

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(objectRequest)
                .build();

        String uploadUrl = s3Presigner.presignPutObject(presignRequest).url().toString();

        Map<String, String> result = new HashMap<>();
        result.put("uploadUrl", uploadUrl);
        result.put("key", key);
        return result;
    }

    public String getPublicUrl(String key) {
        if (publicUrlBase != null && !publicUrlBase.isEmpty()) {
            return publicUrlBase.endsWith("/") ? publicUrlBase + key : publicUrlBase + "/" + key;
        }
        // Fallback to generating a signed URL if no public URL is configured
        // Or return just the key if frontend handles it?
        // Let's generate a long-lived signed URL (e.g. 1 hour) for immediate use?
        // No, that's not permanent.
        // Assuming R2 public bucket access or worker.
        return "https://pub-" + bucketName + ".r2.dev/" + key; // Placeholder default
    }
}
