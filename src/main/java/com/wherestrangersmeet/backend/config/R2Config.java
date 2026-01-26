package com.wherestrangersmeet.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class R2Config {

        @Value("${cloud.aws.credentials.access-key}")
        private String accessKey;

        @Value("${cloud.aws.credentials.secret-key}")
        private String secretKey;

        @Value("${cloud.aws.s3.endpoint}")
        private String endpoint;

        @Bean
        public S3Client s3Client() {
                AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(accessKey, secretKey);

                S3Configuration s3Config = S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .chunkedEncodingEnabled(false)
                                .build();

                return S3Client.builder()
                                .endpointOverride(URI.create(endpoint))
                                .region(Region.US_EAST_1)
                                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                                .serviceConfiguration(s3Config)
                                .httpClientBuilder(ApacheHttpClient.builder())
                                .build();
        }

        @Bean
        public S3Presigner s3Presigner() {
                return S3Presigner.builder()
                                .endpointOverride(URI.create(endpoint))
                                .region(Region.US_EAST_1)
                                .credentialsProvider(StaticCredentialsProvider.create(
                                                AwsBasicCredentials.create(accessKey, secretKey)))
                                .build();
        }
}
