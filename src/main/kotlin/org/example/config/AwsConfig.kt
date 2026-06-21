package org.example.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

@Configuration
class AwsConfig(
    @Value("\${aws.s3.region}") private val region: String,
    @Value("\${aws.access-key:}") private val accessKey: String,
    @Value("\${aws.secret-key:}") private val secretKey: String
) {
    private fun credentialsProvider() =
        if (accessKey.isNotBlank() && secretKey.isNotBlank())
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
        else
            DefaultCredentialsProvider.create()

    @Bean
    fun s3Client(): S3Client = S3Client.builder()
        .region(Region.of(region))
        .credentialsProvider(credentialsProvider())
        .build()

    @Bean
    fun s3Presigner(): S3Presigner = S3Presigner.builder()
        .region(Region.of(region))
        .credentialsProvider(credentialsProvider())
        .build()
}
