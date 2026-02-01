package com.wherestrangersmeet.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final S3Client s3Client;
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

    public java.io.InputStream downloadFile(String key) {
        software.amazon.awssdk.services.s3.model.GetObjectRequest getObjectRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest
                .builder()
                .bucket(bucketName)
                .key(key)
                .build();

        return s3Client.getObject(getObjectRequest);
    }

    @Cacheable(value = "presignedUrls", key = "#key")
    public String generatePresignedUrl(String key) {
        if (key == null) {
            return null;
        }

        // Logic to extract KEY from full URL if mistakenly saved
        // We know our internal keys contain "message-media/" or "user-photos/"
        String cleanKey = key;
        if (key.startsWith("http")) {
            if (key.contains("message-media/")) {
                cleanKey = key.substring(key.indexOf("message-media/"));
                // Remove any query params if present in the stored string
                if (cleanKey.contains("?")) {
                    cleanKey = cleanKey.substring(0, cleanKey.indexOf("?"));
                }
                System.out.println("♻️ Recovered key from URL: " + cleanKey);
            } else if (key.contains("user-photos/")) {
                cleanKey = key.substring(key.indexOf("user-photos/"));
                if (cleanKey.contains("?")) {
                    cleanKey = cleanKey.substring(0, cleanKey.indexOf("?"));
                }
                System.out.println("♻️ Recovered key from URL: " + cleanKey);
            } else {
                // Genuine external URL (e.g. Google Auth)
                return key;
            }
        }

        try {
            software.amazon.awssdk.services.s3.model.GetObjectRequest getObjectRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest
                    .builder()
                    .bucket(bucketName)
                    .key(cleanKey)
                    .build();

            software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest presignRequest = software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
                    .builder()
                    .signatureDuration(Duration.ofHours(1))
                    .getObjectRequest(getObjectRequest)
                    .build();

            String presignedUrl = s3Presigner.presignGetObject(presignRequest).url().toString();
            // System.out.println("Generated new presigned URL for key: " + cleanKey);
            return presignedUrl;

        } catch (Exception e) {
            System.err.println("Error generating presigned URL: " + e.getMessage());
            return key; // Fallback to key
        }
    }

    public String getPublicUrl(String key) {
        // Return the key directly; the Controller will convert it to a Presigned URL
        return key;
    }
}
